package lombok.javac.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.Javac.*;
import static lombok.javac.JavacTreeMaker.TypeTag.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.experimental.Delegate;
import lombok.TollgeDO;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.delombok.LombokOptionsFactory;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TypeTag;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSynchronized;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

/**
 * Handles the {@code lombok.TollgeDO} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleTollgeDO extends JavacAnnotationHandler<TollgeDO> {

    public static final String RETURN_VAR_NAME = "rt";

    @Override public void handle(AnnotationValues<TollgeDO> annotation, JCAnnotation ast, JavacNode annotationNode) {
        handleFlagUsage(annotationNode, ConfigurationKeys.TO_STRING_FLAG_USAGE, "@TollgeDO");
        deleteAnnotationIfNeccessary(annotationNode, TollgeDO.class);
        generateConvertFromRow(ast, annotationNode.up(), annotationNode);
    }

    public void generateConvertFromRow(JCAnnotation ast, JavacNode typeNode, JavacNode source) {

        boolean notAClass = true;
        if (typeNode.get() instanceof JCClassDecl) {
            long flags = ((JCClassDecl) typeNode.get()).mods.flags;
            notAClass = (flags & (Flags.INTERFACE | Flags.ANNOTATION)) != 0;
        }

        if (notAClass) {
            source.addError("@TollgeDO is only supported on a class or enum.");
            return;
        }

        switch (methodExists("convertFromRow", typeNode, 0)) {
            case NOT_EXISTS:
                JCMethodDecl method = createConvertFromRow(ast, typeNode, source);
                injectMethod(typeNode, method);
                break;
            case EXISTS_BY_LOMBOK:
                break;
            default:
            case EXISTS_BY_USER:
                source.addWarning("Not generating convertFromRow(): A method with that name already exists");
                break;
        }
    }

    static JCMethodDecl createConvertFromRow(JCAnnotation ast, JavacNode typeNode, JavacNode source) {

        JavacTreeMaker maker = typeNode.getTreeMaker();

        // return 的类型

        List<JCAnnotation> annsOnReturnType = List.nil();
        if (getCheckerFrameworkVersion(typeNode).generateUnique()) annsOnReturnType = List.of(maker.Annotation(genTypeRef(typeNode, CheckerFrameworkVersion.NAME__UNIQUE), List.<JCExpression>nil()));
        JCExpression returnType = namePlusTypeParamsToTypeReference(maker, typeNode, ((JCClassDecl) typeNode.get()).typarams, annsOnReturnType);

        // 参数
//        List<JCAnnotation> copyableAnnotations = findCopyableAnnotations(fieldNode);
        JCExpression pType = chainDots(typeNode, "io","vertx","mutiny", "sqlclient", "Row");
        JCVariableDecl param = maker.VarDef(maker.Modifiers(Flags.PARAMETER), typeNode.toName("row"), pType, null);
        List<JCVariableDecl> parameters = List.of(param);

        // 构造method body
        ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();

        // new Obj
        JCExpression newClass = maker.NewClass(null, List.<JCExpression>nil(), returnType, List.<JCExpression>nil(), null);
        statements.prepend(maker.VarDef(maker.Modifiers(Flags.FINAL), typeNode.toName(RETURN_VAR_NAME), returnType, newClass));

        // 设置属性值
        for (JavacNode field : typeNode.down()) {

            if (field.getKind() != Kind.FIELD) continue;
            JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
            //Skip fields that start with $
            if (fieldDecl.name.toString().startsWith("$")) continue;
            //Skip static fields.
            if ((fieldDecl.mods.flags & Flags.STATIC) != 0) continue;
            //Skip final fields.
            if ((fieldDecl.mods.flags & Flags.FINAL) != 0) continue;


            String fieldTypeName = fieldDecl.getType().toString();
            String fieldName = fieldDecl.getName().toString();

            addPutStatements(typeNode, maker, statements, field, fieldTypeName, fieldName);

            String fieldNameLine = humpToLine(fieldName);
            if (!fieldName.equals(fieldNameLine)) {
                addPutStatements(typeNode, maker, statements, field, fieldTypeName, fieldNameLine);
            }
        }

        // 返回
        statements.append(maker.Return(maker.Ident(typeNode.toName(RETURN_VAR_NAME))));

        JCBlock methodBody = maker.Block(0, statements.toList());

        // 构造method
        JCMethodDecl methodDef = maker.MethodDef(maker.Modifiers(Flags.PUBLIC | Flags.STATIC), typeNode.toName("convertFromRow"), returnType,
                List.<JCTypeParameter>nil(), parameters, List.<JCExpression>nil(), methodBody, null);
        return recursiveSetGeneratedBy(methodDef, typeNode.get(), source.getContext());
    }

    private static void addPutStatements(JavacNode typeNode,
                                         JavacTreeMaker maker,
                                         ListBuffer<JCStatement> statements,
                                         JavacNode field,
                                         String fieldTypeName,
                                         String fieldName) {
        JCTree.JCExpression getValueApply = maker.Apply(
                List.of(chainDots(typeNode, "java","lang","String")),
                maker.Select(
                        maker.Ident(typeNode.toName("row")),
                        typeNode.toName("get" + initcap(fieldTypeName))
                ),
                List.of((JCExpression)maker.Literal(fieldName))
        );

        JCTree.JCExpression putFieldApply = maker.Apply(
                List.<JCExpression>of(chainDotsString(typeNode, fieldTypeName)),
                chainDotsString(typeNode, RETURN_VAR_NAME + "." + toSetterName(field)),
                List.of(getValueApply)
        );

        JCExpression lessThanZero = maker.Binary(CTC_GREATER_OR_EQUAL,
                maker.Apply(
                        List.of(chainDots(typeNode, "java","lang","String")),
                        maker.Select(
                                maker.Ident(typeNode.toName("row")),
                                typeNode.toName("getColumnIndex")
                        ),
                        List.of((JCExpression)maker.Literal(fieldName))
                ), maker.Literal(0));

        statements.append(maker.If(
                lessThanZero,
                maker.Exec(putFieldApply),
                null
        ));

    }

    public static String initcap(String str) {
        if(str == null || "".equals(str)) {
            return str ;//原样返回
        }
        if(str.length()== 1) {
            return str.toUpperCase() ;//单个字母直接大写
        }
        return str.substring(0,1).toUpperCase() + str.substring(1) ; //利用substring(0,1)将字符串首字母大写，在加上字符串后面的部分。
    }

    public static String humpToLine(String str) {
        return str.replaceAll("[A-Z]", "_$0").toLowerCase();
    }
}

