package nz.bradcampbell.dataparcel;

import android.os.Parcelable;
import com.google.auto.service.AutoService;
import com.google.common.base.Joiner;
import com.squareup.javapoet.*;
import nz.bradcampbell.dataparcel.internal.Parcel;
import nz.bradcampbell.dataparcel.internal.Property;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;

import static javax.lang.model.element.Modifier.*;
import static nz.bradcampbell.dataparcel.internal.PropertyCreator.createProperty;

@AutoService(Processor.class)
public class DataParcelProcessor extends AbstractProcessor {
  private static final String NULLABLE_ANNOTATION_NAME = "Nullable";

  public static final String DATA_VARIABLE_NAME = "data";

  private Elements elementUtils;
  private Filer filer;
  private Types typeUtil;
  private Map<String, Parcel> parcels = new HashMap<String, Parcel>();

  @Override public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    elementUtils = env.getElementUtils();
    filer = env.getFiler();
    typeUtil = env.getTypeUtils();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override public Set<String> getSupportedAnnotationTypes() {
    return Collections.singleton(DataParcel.class.getCanonicalName());
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    if (annotations.isEmpty()) {
      return true;
    }
    for (Element element : roundEnvironment.getElementsAnnotatedWith(DataParcel.class)) {
      if (!(element instanceof TypeElement)) {
        error("@DataParcel applies to a type, " + element.getSimpleName() + " is a " + element.getKind(), element);
        continue;
      }
      TypeElement el = (TypeElement) element;
      createParcel(el);
    }
    for (Parcel p : parcels.values()) {
      try {
        generateJavaFileFor(p).writeTo(filer);
      } catch (IOException e) {
        throw new RuntimeException("An error occurred while writing to filer.", e);
      }
    }
    return true;
  }

  private void createParcel(TypeElement typeElement) {
    String classPackage = getPackageName(typeElement);
    String className = ClassName.get(typeElement).simpleName() + "Parcel";
    if (parcels.containsKey(typeElement.getQualifiedName().toString())) return;
    List<Property> properties = new ArrayList<Property>();
    List<VariableElement> variableElements = getFields(typeElement);
    for (int i = 0; i < variableElements.size(); i++) {
      VariableElement variableElement = variableElements.get(i);
      boolean isNullable = !isFieldRequired(variableElement);
      properties.add(createProperty(typeUtil, isNullable, "component" + (i + 1), variableElement));
    }
    parcels.put(typeElement.getQualifiedName().toString(), new Parcel(properties, classPackage, className, typeElement));
    for (Property property : properties) {
      createClassModelsIfNeeded(property.requiredParcels());
    }
  }

  private void createClassModelsIfNeeded(List<TypeElement> typeElements) {
    if (typeElements == null) return;
    for (TypeElement typeElement : typeElements) {
      if (!parcels.containsKey(typeElement.getQualifiedName().toString())) {
        createParcel(typeElement);
      }
    }
  }

  private List<VariableElement> getFields(TypeElement el) {
    List<? extends Element> enclosedElements = el.getEnclosedElements();
    List<VariableElement> variables = new ArrayList<VariableElement>();
    for (Element e : enclosedElements) {
      if (e instanceof VariableElement && !e.getModifiers().contains(STATIC)) {
        variables.add((VariableElement) e);
      }
    }
    return variables;
  }

  private void error(String message, Element element) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
  }

  private String getPackageName(TypeElement type) {
    return elementUtils.getPackageOf(type).getQualifiedName().toString();
  }

  public static boolean hasAnnotationWithName(Element element, String simpleName) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      String annotationName = mirror.getAnnotationType().asElement().getSimpleName().toString();
      if (simpleName.equals(annotationName)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isFieldRequired(Element element) {
    return !hasAnnotationWithName(element, NULLABLE_ANNOTATION_NAME);
  }

  private JavaFile generateJavaFileFor(Parcel parcel) {
    TypeSpec.Builder o = TypeSpec.classBuilder(parcel.getName())
        .addModifiers(PUBLIC)
        .addSuperinterface(Parcelable.class)
        .addField(generateCreator(parcel))
        .addField(generateContentsField(parcel))
        .addMethod(generateWrapMethod(parcel))
        .addMethod(generateContentsConstructor(parcel))
        .addMethod(generateParcelConstructor(parcel))
        .addMethod(generateGetter(parcel))
        .addMethod(generateDescribeContents())
        .addMethod(generateWriteToParcel(parcel));
    return JavaFile.builder(parcel.getClassPackage(), o.build()).build();
  }

  private FieldSpec generateCreator(Parcel parcel) {
    ClassName className = ClassName.bestGuess(parcel.getName());
    ClassName creator = ClassName.bestGuess("android.os.Parcelable.Creator");
    TypeName creatorOfClass = ParameterizedTypeName.get(creator, className);
    return FieldSpec
        .builder(creatorOfClass, "CREATOR", Modifier.PUBLIC, Modifier.FINAL, Modifier.STATIC)
        .initializer(CodeBlock.builder()
            .beginControlFlow("new $T()", ParameterizedTypeName.get(creator, className))
            .beginControlFlow("@$T public $T createFromParcel($T in)", ClassName.get(Override.class), className,
                ClassName.get(android.os.Parcel.class))
            .addStatement("return new $T(in)", className)
            .endControlFlow()
            .beginControlFlow("@$T public $T[] newArray($T size)", ClassName.get(Override.class), className, int.class)
            .addStatement("return new $T[size]", className)
            .endControlFlow()
            .unindent()
            .add("}")
            .build())
        .build();
  }

  private FieldSpec generateContentsField(Parcel parcel) {
    return FieldSpec.builder(parcel.getTypeName(), DATA_VARIABLE_NAME, PRIVATE, FINAL).build();
  }

  private MethodSpec generateWrapMethod(Parcel parcel) {
    ClassName className = ClassName.bestGuess(parcel.getName());
    return MethodSpec.methodBuilder("wrap")
        .addModifiers(PUBLIC, STATIC, FINAL)
        .addParameter(parcel.getTypeName(), DATA_VARIABLE_NAME)
        .addStatement("return new $T($N)", className, DATA_VARIABLE_NAME)
        .returns(className)
        .build();
  }

  private MethodSpec generateContentsConstructor(Parcel parcel) {
    return MethodSpec.constructorBuilder()
        .addModifiers(PRIVATE)
        .addParameter(parcel.getTypeName(), DATA_VARIABLE_NAME)
        .addStatement("this.$N = $N", DATA_VARIABLE_NAME, DATA_VARIABLE_NAME)
        .build();
  }

  private MethodSpec generateParcelConstructor(Parcel parcel) {
    ParameterSpec in = ParameterSpec
        .builder(ClassName.get("android.os", "Parcel"), "in")
        .build();
    MethodSpec.Builder builder = MethodSpec.constructorBuilder()
        .addModifiers(PRIVATE)
        .addParameter(in);
    List<String> paramNames = new ArrayList<String>();
    for (Property p : parcel.getProperties()) {
      builder.addCode(p.readFromParcel(in));
      paramNames.add(p.getName());
    }
    builder.addStatement("this.$N = new $T($N)", DATA_VARIABLE_NAME, parcel.getTypeName(),
        Joiner.on(", ").join(paramNames));
    return builder.build();
  }

  private MethodSpec generateGetter(Parcel parcel) {
    return MethodSpec.methodBuilder("getContents")
        .addModifiers(PUBLIC)
        .returns(parcel.getTypeName())
        .addStatement("return $N", DATA_VARIABLE_NAME)
        .build();
  }

  private MethodSpec generateDescribeContents() {
    return MethodSpec.methodBuilder("describeContents")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .returns(int.class)
        .addStatement("return 0")
        .build();
  }

  private MethodSpec generateWriteToParcel(Parcel parcel) {
    ParameterSpec dest = ParameterSpec
        .builder(ClassName.get("android.os", "Parcel"), "dest")
        .build();
    MethodSpec.Builder builder = MethodSpec.methodBuilder("writeToParcel")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .addParameter(dest)
        .addParameter(int.class, "flags");
    for (Property p : parcel.getProperties()) {
      builder.addCode(p.writeToParcel(dest));
    }
    return builder.build();
  }
}
