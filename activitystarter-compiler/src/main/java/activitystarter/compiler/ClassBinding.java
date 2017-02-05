package activitystarter.compiler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import activitystarter.Arg;
import activitystarter.Optional;
import activitystarter.compiler.FieldAccessableHelper.FieldVeryfyResult;

import static activitystarter.compiler.ArgumentBinding.getArgumentBindingVariants;
import static activitystarter.compiler.FieldAccessableHelper.FieldVeryfyResult.Accessible;
import static activitystarter.compiler.FieldAccessableHelper.getFieldAccessibility;
import static com.google.auto.common.MoreElements.getPackage;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

final class ClassBinding {

    private static final ClassName UI_THREAD = ClassName.get("android.support.annotation", "UiThread");
    private static final ClassName INTENT = ClassName.get("android.content", "Intent");
    private static final ClassName CONTEXT = ClassName.get("android.content", "Context");

    private final TypeName targetTypeName;
    private final ClassName bindingClassName;
    private final List<ArgumentBinding> argumentBindings;

    ClassBinding(TypeElement enclosingElement) {
        targetTypeName = getTargetTypeName(enclosingElement);
        bindingClassName = getBindingClassName(enclosingElement);
        argumentBindings = getArgAsBindings(enclosingElement.getEnclosedElements());
    }

    private ClassName getBindingClassName(TypeElement enclosingElement) {
        String packageName = getPackage(enclosingElement).getQualifiedName().toString();
        String className = enclosingElement.getQualifiedName().toString().substring(packageName.length() + 1);
        return ClassName.get(packageName, className + "Starter");
    }

    private TypeName getTargetTypeName(TypeElement enclosingElement) {
        TypeName targetType = TypeName.get(enclosingElement.asType());
        if (targetType instanceof ParameterizedTypeName) {
            targetType = ((ParameterizedTypeName) targetType).rawType;
        }
        return targetType;
    }

    private static List<ArgumentBinding> getArgAsBindings(List<? extends Element> allArguments) {
        List<ArgumentBinding> list = new ArrayList<>();
        for (Element e : allArguments) {
            if (e.getAnnotation(Arg.class) != null) list.add(new ArgumentBinding(e));
        }
        return list;
    }

    JavaFile brewJava() {
        return JavaFile.builder(bindingClassName.packageName(), getActivityStarterSpec())
                .addFileComment("Generated code from ActivityStarter. Do not modify!")
                .build();
    }

    private TypeSpec getActivityStarterSpec() {
        TypeSpec.Builder result = TypeSpec
                .classBuilder(bindingClassName.simpleName())
                .addModifiers(PUBLIC, FINAL)
                .addMethod(createFillFieldsMethod());

        for (List<ArgumentBinding> variant : getArgumentBindingVariants(argumentBindings)) {
            result.addMethod(createGetIntentMethod(variant));
            result.addMethod(createStartActivityMethod(variant));
            result.addMethod(createStartActivityMethodWithFlags(variant));
        }

        return result.build();
    }

    private MethodSpec createStartActivityMethod(List<ArgumentBinding> variant) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("start")
                .addAnnotation(UI_THREAD)
                .addParameter(CONTEXT, "context")
                .addModifiers(PUBLIC)
                .addModifiers(STATIC);

        for (ArgumentBinding arg : variant) {
            builder.addParameter(arg.getType(), arg.getName());
        }

        builder.addStatement("$T intent = new Intent(context, $T.class)", INTENT, targetTypeName);
        String argList = "";
        for (ArgumentBinding arg : variant) {
            argList += arg.getName();
            builder.addStatement("intent.putExtra(\"" + arg.getName() + "Arg\", " + arg.getName() + ")");
        }
        builder.addStatement("context.startActivity(intent)");
        return builder.build();
    }

    private MethodSpec createStartActivityMethodWithFlags(List<ArgumentBinding> variant) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("startWithFlags")
                .addAnnotation(UI_THREAD)
                .addParameter(CONTEXT, "context")
                .addModifiers(PUBLIC)
                .addModifiers(STATIC);

        for (ArgumentBinding arg : variant) {
            builder.addParameter(arg.getType(), arg.getName());
        }
        builder.addParameter(TypeName.INT, "flags");

        builder.addStatement("$T intent = new Intent(context, $T.class)", INTENT, targetTypeName);
        for (ArgumentBinding arg : variant) {
            builder.addStatement("intent.putExtra(\"" + arg.getName() + "Arg\", " + arg.getName() + ")");
        }
        builder.addStatement("intent.addFlags(flags)");
        builder.addStatement("context.startActivity(intent)");
        return builder.build();
    }

    private MethodSpec createGetIntentMethod(List<ArgumentBinding> variant) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("getIntent")
                .addAnnotation(UI_THREAD)
                .addParameter(CONTEXT, "context")
                .returns(INTENT)
                .addModifiers(PUBLIC)
                .addModifiers(STATIC);

        for (ArgumentBinding arg : variant) {
            builder.addParameter(arg.getType(), arg.getName());
        }

        builder.addStatement("$T intent = new Intent(context, $T.class)", INTENT, targetTypeName);
        for (ArgumentBinding arg : variant) {
            builder.addStatement("intent.putExtra(\"" + arg.getName() + "Arg\", " + arg.getName() + ")");
        }
        builder.addStatement("return intent");
        return builder.build();
    }

    private MethodSpec createFillFieldsMethod() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("fill")
                .addAnnotation(UI_THREAD)
                .addParameter(targetTypeName, "activity")
                .addModifiers(PUBLIC)
                .addModifiers(STATIC);

        if (argumentBindings.size() > 0)
            builder.addStatement("Intent intent = activity.getIntent()");

        for (ArgumentBinding arg : argumentBindings) {
            String fieldName = arg.getName();
            String keyName = fieldName + "Arg";

            FieldVeryfyResult settingType = arg.getSettingType();
            if (settingType == Accessible)
                builder.addStatement("if(intent.hasExtra(\"" + keyName + "\")) activity." + fieldName + " = " + getIntentGetterFor(arg, keyName));
            else {
                String setter = FieldAccessableHelper.getSetter(settingType, fieldName);
                builder.addStatement("if(intent.hasExtra(\"" + keyName + "\")) activity." + setter + "(" + getIntentGetterFor(arg, keyName) + ")");
            }
        }

        return builder.build();
    }

    private String getIntentGetterFor(ArgumentBinding arg, String keyName) {
        TypeName name = arg.getType();
        if (name.equals(TypeName.get(String.class)))
            return "intent.getStringExtra(\"" + keyName + "\")";
        else if (name.equals(TypeName.INT))
            return "intent.getIntExtra(\"" + keyName + "\", -1)";
        else if (name.equals(TypeName.FLOAT))
            return "intent.getFloatExtra(\"" + keyName + "\", -1F)";
        else if (name.equals(TypeName.BOOLEAN))
            return "intent.getBooleanExtra(\"" + keyName + "\", false)";
        else if (name.equals(TypeName.DOUBLE))
            return "intent.getDoubleExtra(\"" + keyName + "\", -1D)";
        else if (name.equals(TypeName.CHAR))
            return "intent.getCharExtra(\"" + keyName + "\", 'a')";
        else if (IsSubtypeHelper.isSubtypeOfType(arg.getElementType(), "android.os.Parcelable"))
            return "(" + name + ") intent.getParcelableExtra(\"" + keyName + "\")";
        else if (IsSubtypeHelper.isSubtypeOfType(arg.getElementType(), "java.io.Serializable"))
            return "(" + name + ") intent.getSerializableExtra(\"" + keyName + "\")";
        else
            throw new Error("Illegal field type" + arg.getType());
    }
}
