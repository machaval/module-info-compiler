package org.glavo.mic;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleDirective;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import com.github.javaparser.ast.modules.ModuleOpensDirective;
import com.github.javaparser.ast.modules.ModuleProvidesDirective;
import com.github.javaparser.ast.modules.ModuleRequiresDirective;
import com.github.javaparser.ast.modules.ModuleUsesDirective;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ModuleInfoCompiler {
    public static final int DEFAULT_TARGET_COMPATIBILITY = 9;

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final int targetCompatibility;
    private final String moduleVersion;
    private final String mainClass;

    public ModuleInfoCompiler() {
        targetCompatibility = DEFAULT_TARGET_COMPATIBILITY;
        moduleVersion = null;
        mainClass = null;
    }

    public ModuleInfoCompiler(int targetCompatibility, String moduleVersion, String mainClass) {
        if (targetCompatibility < 9) {
            throw new IllegalArgumentException();
        }
        this.targetCompatibility = targetCompatibility;
        this.moduleVersion = moduleVersion;
        this.mainClass = mainClass;
    }

    private static String[] moduleNameListToArray(NodeList<Name> list) {
        if (list.isEmpty()) {
            return EMPTY_STRING_ARRAY;
        }

        String[] res = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            res[i] = list.get(i).asString();
        }
        return res;
    }

    private static String[] packageNameListToArray(NodeList<Name> list) {
        if (list.isEmpty()) {
            return EMPTY_STRING_ARRAY;
        }

        String[] res = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            res[i] = list.get(i).asString().replace('.', '/');
        }
        return res;
    }

    public void compile(Path source, Path target) throws IOException {
        try (Reader reader = Files.newBufferedReader(source);
             OutputStream output = Files.newOutputStream(target)) {
            compile(reader, output);
        }
    }

    public void compile(Reader source, OutputStream target) throws IOException {
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(source);
            ModuleDeclaration md = compilationUnit.getModule().orElseThrow(() -> new IOException("Module not found in source code"));

            ClassWriter classWriter = new ClassWriter(0);

            if (md.getAnnotations() != null) {
                md.getAnnotations().forEach(ant -> {
                    AnnotationVisitor annotationVisitor = classWriter.visitAnnotation("L" + ant.getNameAsString().replace('.', '/') + ";",true);
                    if (ant.isSingleMemberAnnotationExpr()) {
                        visitAnnotationValue(annotationVisitor, "value", ant.asSingleMemberAnnotationExpr().getMemberValue());
                    } else if (ant.isNormalAnnotationExpr()) {
                        for (MemberValuePair pair : ant.asNormalAnnotationExpr().getPairs()) {
                            visitAnnotationValue(annotationVisitor, pair.getNameAsString(), pair.getValue());
                        }
                    }
                    annotationVisitor.visitEnd();
                });
            }

            classWriter.visit((44 + targetCompatibility), Opcodes.ACC_MODULE, "module-info", null, null, null);

            ModuleVisitor moduleVisitor = classWriter.visitModule(md.getNameAsString(), md.isOpen() ? Opcodes.ACC_OPEN : 0, moduleVersion);
            if (mainClass != null) {
                moduleVisitor.visitMainClass(mainClass.replace('.', '/'));
            }

            if (!md.getNameAsString().equals("java.base")) {
                moduleVisitor.visitRequire("java.base", 0, null);
            }

            for (ModuleDirective directive : md.getDirectives()) {
                if (directive.isModuleExportsDirective()) {
                    ModuleExportsDirective export = directive.asModuleExportsDirective();
                    moduleVisitor.visitExport(export.getNameAsString().replace('.', '/'), 0, moduleNameListToArray(export.getModuleNames()));
                } else if (directive.isModuleOpensDirective()) {
                    ModuleOpensDirective open = directive.asModuleOpensDirective();
                    moduleVisitor.visitOpen(open.getNameAsString().replace('.', '/'), 0, moduleNameListToArray(open.getModuleNames()));
                } else if (directive.isModuleProvidesDirective()) {
                    ModuleProvidesDirective provides = directive.asModuleProvidesDirective();
                    moduleVisitor.visitProvide(provides.getNameAsString().replace('.', '/'), packageNameListToArray(provides.getWith()));
                } else if (directive.isModuleRequiresDirective()) {
                    ModuleRequiresDirective requires = directive.asModuleRequiresDirective();
                    if (!requires.getNameAsString().equals("java.base")) {
                        int access = 0;
                        for (Modifier modifier : requires.getModifiers()) {
                            if (modifier.getKeyword() == Modifier.Keyword.STATIC) {
                                access |= Opcodes.ACC_STATIC_PHASE;
                            } else if (modifier.getKeyword() == Modifier.Keyword.TRANSITIVE) {
                                access |= Opcodes.ACC_TRANSITIVE;
                            }
                        }

                        moduleVisitor.visitRequire(requires.getNameAsString(), access, null);
                    }
                } else if (directive.isModuleUsesDirective()) {
                    ModuleUsesDirective uses = directive.asModuleUsesDirective();
                    moduleVisitor.visitUse(uses.getNameAsString().replace('.', '/'));
                } else {
                    throw new AssertionError("Unknown module directive: " + directive);
                }
            }

            target.write(classWriter.toByteArray());

        } catch (ParseProblemException e) {
            throw new IOException(e);
        }
    }

    private void visitAnnotationValue(AnnotationVisitor visitor, String name, Expression value) {
        if (value.isStringLiteralExpr()) {
            visitor.visit(name, value.asStringLiteralExpr().getValue());
        } else if (value.isIntegerLiteralExpr()) {
            visitor.visit(name, Integer.valueOf(value.asIntegerLiteralExpr().getValue()));
        } else if (value.isLongLiteralExpr()) {
            visitor.visit(name, Long.valueOf(value.asLongLiteralExpr().getValue().replace("L", "").replace("l", "")));
        } else if (value.isDoubleLiteralExpr()) {
            visitor.visit(name, Double.valueOf(value.asDoubleLiteralExpr().getValue().replace("D", "").replace("d", "")));
        } else if (value.isDoubleLiteralExpr() && (value.asDoubleLiteralExpr().getValue().endsWith("f") || value.asDoubleLiteralExpr().getValue().endsWith("F"))) {
            String floatValue = value.asDoubleLiteralExpr().getValue().replace("F", "").replace("f", "");
            visitor.visit(name, Float.valueOf(floatValue));
        } else if (value.isBooleanLiteralExpr()) {
            visitor.visit(name, value.asBooleanLiteralExpr().getValue());
        } else if (value.isClassExpr()) {
            visitor.visit(name, org.objectweb.asm.Type.getType("L" + value.asClassExpr().getTypeAsString().replace('.', '/') + ";"));
        } else if (value.isArrayInitializerExpr()) {
            ArrayInitializerExpr arrayExpr = value.asArrayInitializerExpr();
            AnnotationVisitor arrayVisitor = visitor.visitArray(name);
            for (Expression element : arrayExpr.getValues()) {
                visitAnnotationValue(arrayVisitor, null, element);
            }
            arrayVisitor.visitEnd();
        } else if (value.isAnnotationExpr()) {
            AnnotationExpr annotationExpr = value.asAnnotationExpr();
            AnnotationVisitor nestedVisitor = visitor.visitAnnotation(name, "L" + annotationExpr.getNameAsString().replace('.', '/') + ";");
            if (annotationExpr.isSingleMemberAnnotationExpr()) {
                visitAnnotationValue(nestedVisitor, "value", annotationExpr.asSingleMemberAnnotationExpr().getMemberValue());
            } else if (annotationExpr.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : annotationExpr.asNormalAnnotationExpr().getPairs()) {
                    visitAnnotationValue(nestedVisitor, pair.getNameAsString(), pair.getValue());
                }
            }
            nestedVisitor.visitEnd();
        } else if (value.isFieldAccessExpr()) {
            // Handle enum constants
            FieldAccessExpr fieldExpr = value.asFieldAccessExpr();
            visitor.visitEnum(name, "L" + fieldExpr.getScope().toString().replace('.', '/') + ";", fieldExpr.getNameAsString());
        } else {
            // For other expressions, try to convert to string as fallback
            visitor.visit(name, value.toString());
        }
    }

    public static void main(String[] args) throws IOException {
        String HELP_MESSAGE = "Usage: gmic [-target <target compatibility>] [-main-class <module main class>] [-module-version <module version>] <source file> <target file>";

        int targetCompatibility = DEFAULT_TARGET_COMPATIBILITY;
        String moduleVersion = null;
        String mainClass = null;

        int i = 0;
        loop:
        while (i < args.length) {
            String arg = args[i];

            switch (arg) {
                case "-?":
                case "-help":
                case "--help":
                    System.out.println(HELP_MESSAGE);
                    return;
                case "-target":
                case "--target":
                    if (i == args.length - 1) {
                        System.err.println(arg + "  requires an argument");
                        System.exit(1);
                        return;
                    }

                    targetCompatibility = Integer.parseInt(args[++i]);
                    if (targetCompatibility < 9) {
                        System.err.println("The target version needs to be greater than or equal to 9");
                        System.exit(1);
                        return;
                    }
                    break;
                case "-module-version":
                case "--module-version":
                    if (i == args.length - 1) {
                        System.err.println(arg + "  requires an argument");
                        System.exit(1);
                        return;
                    }

                    moduleVersion = args[++i];
                    break;
                case "-main-class":
                case "--main-class":
                    if (i == args.length - 1) {
                        System.err.println(arg + "  requires an argument");
                        System.exit(1);
                        return;
                    }

                    mainClass = args[++i];
                    break;
                default:
                    break loop;
            }
            i++;
        }

        if (args.length - i != 2) {
            System.err.println(HELP_MESSAGE);
            System.exit(1);
            return;
        }

        Path sourceFile = Paths.get(args[i]);
        Path targetFile = Paths.get(args[i + 1]);

        ModuleInfoCompiler compiler = new ModuleInfoCompiler(targetCompatibility, moduleVersion, mainClass);
        compiler.compile(sourceFile, targetFile);
    }
}
