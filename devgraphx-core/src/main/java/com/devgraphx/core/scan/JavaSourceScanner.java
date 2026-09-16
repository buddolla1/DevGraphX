package com.devgraphx.core.scan;

import com.devgraphx.core.model.CodeEdge;
import com.devgraphx.core.model.CodeGraph;
import com.devgraphx.core.model.CodeNode;
import com.devgraphx.core.model.GraphBuilder;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VarType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class JavaSourceScanner {
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "Boolean", "Byte", "Character", "CharSequence", "Class", "Double", "Enum", "Exception",
            "Float", "Integer", "Iterable", "Long", "Number", "Object", "RuntimeException",
            "Short", "String", "StringBuilder", "StringBuffer", "Throwable", "Void"
    );
    private static final Set<String> DEFAULT_EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".mvn", "build", "target", "out", "bin", "node_modules"
    );

    static {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private final Path repositoryRoot;
    private final List<Path> includes;
    private final List<Path> excludes;
    private final boolean includeTests;
    private final GraphBuilder graph = new GraphBuilder();
    private final Map<String, TypeInfo> typesById = new LinkedHashMap<>();
    private final Map<String, List<String>> typeIdsBySimpleName = new HashMap<>();
    private final Map<String, MethodInfo> methodsById = new LinkedHashMap<>();
    private final Map<String, Map<String, List<MethodInfo>>> methodsByOwnerAndName = new HashMap<>();

    public JavaSourceScanner(Path repositoryRoot) {
        this(repositoryRoot, List.of(), List.of(), true);
    }

    public JavaSourceScanner(Path repositoryRoot, List<Path> includes, List<Path> excludes, boolean includeTests) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.includes = includes.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.excludes = excludes.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.includeTests = includeTests;
    }

    public CodeGraph scan() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(repositoryRoot)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(this::included)
                    .filter(path -> !excluded(path))
                    .sorted()
                    .toList();
        }

        Map<Path, CompilationUnit> units = new LinkedHashMap<>();
        for (Path file : files) {
            units.put(file, parse(file));
        }
        units.forEach(this::collectTypes);
        units.forEach(this::collectMethods);
        units.forEach(this::collectRelationships);
        return graph.build();
    }

    private CompilationUnit parse(Path file) throws IOException {
        return StaticJavaParser.parse(file);
    }

    private boolean included(Path file) {
        String relativePath = relative(file).replace('\\', '/');
        if (!includeTests && (relativePath.startsWith("src/test/java/") || relativePath.contains("/src/test/java/"))) {
            return false;
        }
        return includes.isEmpty() || includes.stream().anyMatch(file::startsWith);
    }

    private boolean excluded(Path file) {
        if (excludes.stream().anyMatch(file::startsWith)) {
            return true;
        }
        Path relative = repositoryRoot.relativize(file.toAbsolutePath().normalize());
        for (Path part : relative) {
            if (DEFAULT_EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private void collectTypes(Path file, CompilationUnit unit) {
        String fileId = fileId(file);
        graph.node(new CodeNode(fileId, relative(file), "file", relative(file), 1, fileEndLine(file)));

        String packageName = packageName(unit);
        unit.findAll(TypeDeclaration.class).forEach(rawType -> {
            TypeDeclaration<?> type = (TypeDeclaration<?>) rawType;
            String typeId = typeId(packageName, type);
            TypeInfo typeInfo = new TypeInfo(typeId, type.getNameAsString(), packageName, relative(file));
            typesById.put(typeId, typeInfo);
            typeIdsBySimpleName.computeIfAbsent(type.getNameAsString(), ignored -> new ArrayList<>()).add(typeId);
            graph.node(new CodeNode(typeId, type.getNameAsString(), typeKind(type), relative(file), startLine(type), endLine(type)));
            graph.edge(new CodeEdge(fileId, typeId, "contains", relative(file), startLine(type)));
        });
    }

    private void collectMethods(Path file, CompilationUnit unit) {
        String packageName = packageName(unit);
        Map<String, String> imports = imports(unit);
        unit.findAll(TypeDeclaration.class).forEach(rawType -> {
            TypeDeclaration<?> type = (TypeDeclaration<?>) rawType;
            String ownerTypeId = typeId(packageName, type);
            TypeInfo typeInfo = typesById.get(ownerTypeId);
            if (typeInfo == null) {
                return;
            }
            type.getMembers().forEach(member -> {
                if (member instanceof MethodDeclaration method) {
                    MethodInfo methodInfo = methodInfo(file, imports, ownerTypeId, method, false);
                    registerMethod(methodInfo);
                    graph.node(methodNode(methodInfo, "method"));
                    graph.edge(new CodeEdge(ownerTypeId, methodInfo.id(), "contains", relative(file), startLine(method)));
                    graph.edge(new CodeEdge(methodInfo.id(), ownerTypeId, "belongs-to-class", relative(file), startLine(method)));
                } else if (member instanceof ConstructorDeclaration constructor) {
                    MethodInfo constructorInfo = methodInfo(file, imports, ownerTypeId, constructor, true);
                    registerMethod(constructorInfo);
                    graph.node(methodNode(constructorInfo, "constructor"));
                    graph.edge(new CodeEdge(ownerTypeId, constructorInfo.id(), "contains", relative(file), startLine(constructor)));
                    graph.edge(new CodeEdge(constructorInfo.id(), ownerTypeId, "belongs-to-class", relative(file), startLine(constructor)));
                }
            });
        });
    }

    private void collectRelationships(Path file, CompilationUnit unit) {
        Map<String, String> imports = imports(unit);
        imports.forEach((simple, imported) -> {
            String fileId = fileId(file);
            graph.node(new CodeNode(imported, simple, "external_type", "", 0, 0));
            unit.getImports().stream()
                    .filter(importDeclaration -> importDeclaration.getNameAsString().equals(imported))
                    .findFirst()
                    .ifPresent(importDeclaration -> graph.edge(new CodeEdge(fileId, imported, "imports", relative(file), startLine(importDeclaration))));
        });

        String packageName = packageName(unit);
        unit.findAll(TypeDeclaration.class).forEach(rawType -> {
            TypeDeclaration<?> type = (TypeDeclaration<?>) rawType;
            String ownerTypeId = typeId(packageName, type);
            collectTypeRelationships(file, imports, ownerTypeId, type);
            collectFieldTypes(file, imports, ownerTypeId, type);
            for (BodyDeclaration<?> member : type.getMembers()) {
                collectMemberRelationships(file, imports, ownerTypeId, member);
            }
        });
    }

    private void collectTypeRelationships(Path file, Map<String, String> imports, String ownerTypeId, TypeDeclaration<?> type) {
        TypeInfo owner = typesById.get(ownerTypeId);
        if (type instanceof ClassOrInterfaceDeclaration classDecl) {
            addTypeReferences(file, imports, ownerTypeId, classDecl.getExtendedTypes(), "extends");
            classDecl.getExtendedTypes().stream()
                    .findFirst()
                    .flatMap(extended -> resolveType(imports, owner == null ? "" : owner.packageName(), extended))
                    .ifPresent(extended -> {
                        if (owner != null) {
                            owner.superClassId(extended);
                        }
                    });
            addTypeReferences(file, imports, ownerTypeId, classDecl.getImplementedTypes(), "implements");
            classDecl.getImplementedTypes().forEach(implemented -> resolveType(imports, owner == null ? "" : owner.packageName(), implemented)
                    .ifPresent(interfaceId -> {
                        if (owner != null) {
                            owner.interfaceIds().add(interfaceId);
                        }
                    }));
        } else if (type instanceof EnumDeclaration enumDecl) {
            addTypeReferences(file, imports, ownerTypeId, enumDecl.getImplementedTypes(), "implements");
        } else if (type instanceof RecordDeclaration recordDecl) {
            addTypeReferences(file, imports, ownerTypeId, recordDecl.getImplementedTypes(), "implements");
        }
    }

    private void collectFieldTypes(Path file, Map<String, String> imports, String ownerTypeId, TypeDeclaration<?> type) {
        TypeInfo owner = typesById.get(ownerTypeId);
        if (owner == null) {
            return;
        }
        type.getFields().forEach(field -> field.getVariables().forEach(variable -> {
            String typeName = normalizeType(imports, owner.packageName(), variable.getType());
            owner.fields().put(variable.getNameAsString(), typeName);
            addTypeReference(file, imports, ownerTypeId, variable.getType(), "references");
        }));
    }

    private void collectMemberRelationships(Path file, Map<String, String> imports, String ownerTypeId, BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration method) {
            MethodInfo methodInfo = findDeclaredMethod(ownerTypeId, method.getNameAsString(), parameterTypes(imports, ownerTypeId, method));
            if (methodInfo == null) {
                return;
            }
            addTypeReference(file, imports, methodInfo.id(), method.getType(), "returns");
            method.getParameters().forEach(parameter -> addTypeReference(file, imports, methodInfo.id(), parameter.getType(), "references"));
            collectCalls(file, imports, ownerTypeId, methodInfo, method);
            collectLocalTypeReferences(file, imports, methodInfo.id(), method);
        } else if (member instanceof ConstructorDeclaration constructor) {
            MethodInfo constructorInfo = findDeclaredMethod(ownerTypeId, "<init>", parameterTypes(imports, ownerTypeId, constructor));
            if (constructorInfo == null) {
                return;
            }
            constructor.getParameters().forEach(parameter -> addTypeReference(file, imports, constructorInfo.id(), parameter.getType(), "references"));
            collectCalls(file, imports, ownerTypeId, constructorInfo, constructor);
            collectLocalTypeReferences(file, imports, constructorInfo.id(), constructor);
        } else {
            member.findAll(ClassOrInterfaceType.class)
                    .forEach(type -> addTypeReference(file, imports, ownerTypeId, type, "references"));
        }
    }

    private void collectCalls(Path file, Map<String, String> imports, String ownerTypeId, MethodInfo caller, com.github.javaparser.ast.Node scope) {
        Map<String, String> variables = variableTypes(imports, ownerTypeId, scope);
        scope.findAll(MethodCallExpr.class).forEach(call -> {
            Optional<MethodInfo> target = resolveCallTarget(imports, ownerTypeId, variables, call);
            if (target.isPresent()) {
                MethodInfo method = target.get();
                graph.edge(new CodeEdge(caller.id(), method.id(), "calls", relative(file), startLine(call)));
                graph.edge(new CodeEdge(method.id(), caller.id(), "called-by", relative(file), startLine(call)));
            } else {
                graph.unresolvedMethodCall();
            }
        });
        scope.findAll(ObjectCreationExpr.class).forEach(call -> {
            Optional<String> owner = resolveType(imports, packageFor(ownerTypeId), call.getType());
            Optional<MethodInfo> target = owner.flatMap(ownerId -> resolveMethod(ownerId, "<init>", call.getArguments().size()));
            if (target.isPresent()) {
                MethodInfo method = target.get();
                graph.edge(new CodeEdge(caller.id(), method.id(), "calls", relative(file), startLine(call)));
                graph.edge(new CodeEdge(method.id(), caller.id(), "called-by", relative(file), startLine(call)));
            } else {
                graph.unresolvedMethodCall();
            }
        });
    }

    private void collectLocalTypeReferences(Path file, Map<String, String> imports, String ownerId, com.github.javaparser.ast.Node scope) {
        scope.findAll(ClassOrInterfaceType.class)
                .forEach(type -> addTypeReference(file, imports, ownerId, type, "references"));
    }

    private Map<String, String> variableTypes(Map<String, String> imports, String ownerTypeId, com.github.javaparser.ast.Node scope) {
        Map<String, String> variables = new HashMap<>();
        TypeInfo owner = typesById.get(ownerTypeId);
        if (owner != null) {
            variables.putAll(owner.fields());
        }
        if (scope instanceof CallableDeclaration<?> callable) {
            callable.getParameters().forEach(parameter ->
                    variables.put(parameter.getNameAsString(), normalizeType(imports, packageFor(ownerTypeId), parameter.getType())));
        }
        scope.findAll(VariableDeclarator.class).forEach(variable ->
                variables.put(variable.getNameAsString(), normalizeType(imports, packageFor(ownerTypeId), variable.getType())));
        return variables;
    }

    private Optional<MethodInfo> resolveCallTarget(Map<String, String> imports, String ownerTypeId, Map<String, String> variables, MethodCallExpr call) {
        int arity = call.getArguments().size();
        List<String> argumentTypes = argumentTypes(variables, call.getArguments());
        if (call.getScope().isEmpty()) {
            return resolveMethod(ownerTypeId, call.getNameAsString(), arity, argumentTypes)
                    .or(() -> resolveInheritedMethod(ownerTypeId, call.getNameAsString(), arity, argumentTypes));
        }

        Expression scope = call.getScope().get();
        if (scope instanceof ThisExpr) {
            return resolveMethod(ownerTypeId, call.getNameAsString(), arity, argumentTypes)
                    .or(() -> resolveInheritedMethod(ownerTypeId, call.getNameAsString(), arity, argumentTypes));
        }
        if (scope instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            String variableType = variables.get(name);
            if (variableType != null) {
                return resolveMethod(variableType, call.getNameAsString(), arity, argumentTypes)
                        .or(() -> resolveInheritedMethod(variableType, call.getNameAsString(), arity, argumentTypes));
            }
            Optional<String> typeId = resolveType(imports, packageFor(ownerTypeId), name);
            if (typeId.isPresent()) {
                return resolveMethod(typeId.get(), call.getNameAsString(), arity, argumentTypes);
            }
        }
        return Optional.empty();
    }

    private Optional<MethodInfo> resolveInheritedMethod(String ownerTypeId, String methodName, int arity, List<String> argumentTypes) {
        TypeInfo type = typesById.get(ownerTypeId);
        if (type == null) {
            return Optional.empty();
        }
        if (!type.superClassId().isBlank()) {
            Optional<MethodInfo> superMethod = resolveMethod(type.superClassId(), methodName, arity, argumentTypes);
            if (superMethod.isPresent()) {
                return superMethod;
            }
        }
        for (String interfaceId : type.interfaceIds()) {
            Optional<MethodInfo> interfaceMethod = resolveMethod(interfaceId, methodName, arity, argumentTypes);
            if (interfaceMethod.isPresent()) {
                return interfaceMethod;
            }
        }
        return Optional.empty();
    }

    private Optional<MethodInfo> resolveMethod(String ownerTypeId, String methodName, int arity) {
        return resolveMethod(ownerTypeId, methodName, arity, List.of());
    }

    private Optional<MethodInfo> resolveMethod(String ownerTypeId, String methodName, int arity, List<String> argumentTypes) {
        List<MethodInfo> candidates = methodsByOwnerAndName
                .getOrDefault(ownerTypeId, Map.of())
                .getOrDefault(methodName, List.of())
                .stream()
                .filter(method -> method.parameterTypes().size() == arity)
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (!argumentTypes.isEmpty() && argumentTypes.stream().allMatch(type -> !type.isBlank())) {
            Optional<MethodInfo> exact = candidates.stream()
                    .filter(method -> method.parameterTypes().equals(argumentTypes))
                    .findFirst();
            if (exact.isPresent()) {
                return exact;
            }
        }
        return candidates.stream().min(Comparator.comparing(MethodInfo::id));
    }

    private List<String> argumentTypes(Map<String, String> variables, NodeList<Expression> arguments) {
        return arguments.stream()
                .map(argument -> argumentType(variables, argument))
                .toList();
    }

    private String argumentType(Map<String, String> variables, Expression argument) {
        if (argument instanceof NameExpr nameExpr) {
            return variables.getOrDefault(nameExpr.getNameAsString(), "");
        }
        if (argument instanceof StringLiteralExpr) {
            return "java.lang.String";
        }
        if (argument instanceof IntegerLiteralExpr) {
            return "java.lang.Integer";
        }
        if (argument instanceof LongLiteralExpr) {
            return "java.lang.Long";
        }
        if (argument.isBooleanLiteralExpr()) {
            return "java.lang.Boolean";
        }
        if (argument.isCharLiteralExpr()) {
            return "java.lang.Character";
        }
        if (argument.isDoubleLiteralExpr()) {
            return "java.lang.Double";
        }
        return "";
    }

    private MethodInfo findDeclaredMethod(String ownerTypeId, String methodName, List<String> parameterTypes) {
        return methodsByOwnerAndName
                .getOrDefault(ownerTypeId, Map.of())
                .getOrDefault(methodName, List.of())
                .stream()
                .filter(method -> method.parameterTypes().equals(parameterTypes))
                .findFirst()
                .orElse(null);
    }

    private void addTypeReferences(Path file, Map<String, String> imports, String sourceId, NodeList<ClassOrInterfaceType> types, String relation) {
        types.forEach(type -> addTypeReference(file, imports, sourceId, type, relation));
    }

    private void addTypeReference(Path file, Map<String, String> imports, String sourceId, Type type, String relation) {
        type.findAll(ClassOrInterfaceType.class).forEach(found -> addTypeReference(file, imports, sourceId, found, relation));
    }

    private void addTypeReference(Path file, Map<String, String> imports, String sourceId, ClassOrInterfaceType type, String relation) {
        String targetId = resolveType(imports, packageFor(sourceId), type).orElse(type.getNameAsString());
        graph.node(new CodeNode(targetId, type.getNameAsString(), targetKind(targetId), sourceFileFor(targetId), 0, 0));
        graph.edge(new CodeEdge(sourceId, targetId, relation, relative(file), startLine(type)));
    }

    private void registerMethod(MethodInfo methodInfo) {
        methodsById.put(methodInfo.id(), methodInfo);
        methodsByOwnerAndName
                .computeIfAbsent(methodInfo.ownerTypeId(), ignored -> new HashMap<>())
                .computeIfAbsent(methodInfo.methodName(), ignored -> new ArrayList<>())
                .add(methodInfo);
    }

    private CodeNode methodNode(MethodInfo methodInfo, String kind) {
        String label = methodInfo.ownerSimpleName() + "." + methodInfo.methodName() + "("
                + String.join(", ", methodInfo.parameterTypes()) + ")";
        return new CodeNode(
                methodInfo.id(),
                label,
                kind,
                methodInfo.sourceFile(),
                methodInfo.startLine(),
                methodInfo.endLine(),
                methodInfo.methodName(),
                methodInfo.ownerSimpleName(),
                methodInfo.ownerTypeId(),
                methodInfo.signature(),
                methodInfo.visibility(),
                methodInfo.returnType(),
                methodInfo.parameterTypes()
        );
    }

    private MethodInfo methodInfo(Path file, Map<String, String> imports, String ownerTypeId, MethodDeclaration method, boolean constructor) {
        List<String> parameterTypes = parameterTypes(imports, ownerTypeId, method);
        String name = constructor ? "<init>" : method.getNameAsString();
        String returnType = normalizeType(imports, packageFor(ownerTypeId), method.getType());
        return methodInfo(file, ownerTypeId, method, name, returnType, parameterTypes);
    }

    private MethodInfo methodInfo(Path file, Map<String, String> imports, String ownerTypeId, ConstructorDeclaration constructor, boolean constructorFlag) {
        List<String> parameterTypes = parameterTypes(imports, ownerTypeId, constructor);
        return methodInfo(file, ownerTypeId, constructor, "<init>", ownerTypeId, parameterTypes);
    }

    private MethodInfo methodInfo(Path file, String ownerTypeId, CallableDeclaration<?> callable, String name, String returnType, List<String> parameterTypes) {
        String signature = ownerTypeId + "." + name + "(" + String.join(",", parameterTypes) + ")";
        return new MethodInfo(
                signature,
                name,
                ownerTypeId,
                simpleName(ownerTypeId),
                signature,
                visibility(callable),
                returnType,
                parameterTypes,
                relative(file),
                startLine(callable),
                endLine(callable)
        );
    }

    private List<String> parameterTypes(Map<String, String> imports, String ownerTypeId, CallableDeclaration<?> callable) {
        return callable.getParameters().stream()
                .map(Parameter::getType)
                .map(type -> normalizeType(imports, packageFor(ownerTypeId), type))
                .toList();
    }

    private String normalizeType(Map<String, String> imports, String packageName, Type type) {
        if (type instanceof PrimitiveType || type instanceof VarType) {
            return type.asString();
        }
        if (type instanceof ArrayType arrayType) {
            return normalizeType(imports, packageName, arrayType.getComponentType()) + "[]";
        }
        if (type instanceof ClassOrInterfaceType classType) {
            return resolveType(imports, packageName, classType).orElse(classType.getNameAsString());
        }
        return type.asString();
    }

    private Optional<String> resolveType(Map<String, String> imports, String packageName, ClassOrInterfaceType type) {
        return resolveType(imports, packageName, type.getNameAsString());
    }

    private Optional<String> resolveType(Map<String, String> imports, String packageName, String name) {
        if (typesById.containsKey(name)) {
            return Optional.of(name);
        }
        String imported = imports.get(name);
        if (imported != null) {
            return Optional.of(imported);
        }
        if (!packageName.isBlank() && typesById.containsKey(packageName + "." + name)) {
            return Optional.of(packageName + "." + name);
        }
        if (JAVA_LANG_TYPES.contains(name)) {
            return Optional.of("java.lang." + name);
        }
        List<String> localMatches = typeIdsBySimpleName.getOrDefault(name, List.of());
        if (localMatches.size() == 1) {
            return Optional.of(localMatches.getFirst());
        }
        return Optional.empty();
    }

    private Map<String, String> imports(CompilationUnit unit) {
        Map<String, String> imports = new HashMap<>();
        unit.getImports().forEach(importDeclaration -> {
            if (importDeclaration.isAsterisk()) {
                return;
            }
            String imported = importDeclaration.getNameAsString();
            String simple = imported.substring(imported.lastIndexOf('.') + 1);
            imports.put(simple, imported);
        });
        return imports;
    }

    private String visibility(CallableDeclaration<?> callable) {
        if (callable.isPublic()) {
            return "public";
        }
        if (callable.isProtected()) {
            return "protected";
        }
        if (callable.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private String packageName(CompilationUnit unit) {
        return unit.getPackageDeclaration()
                .map(declaration -> declaration.getName().asString())
                .orElse("");
    }

    private String targetKind(String id) {
        return typesById.containsKey(id) ? "type" : "external_type";
    }

    private String sourceFileFor(String id) {
        TypeInfo type = typesById.get(id);
        return type == null ? "" : type.sourceFile();
    }

    private String typeId(String packageName, TypeDeclaration<?> type) {
        String name = nestedPrefix(type).map(prefix -> prefix + "." + type.getNameAsString()).orElse(type.getNameAsString());
        return packageName.isBlank() ? name : packageName + "." + name;
    }

    private Optional<String> nestedPrefix(TypeDeclaration<?> type) {
        return type.findAncestor(TypeDeclaration.class).map(NodeWithSimpleName::getNameAsString);
    }

    private String typeKind(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        return "type";
    }

    private String fileId(Path file) {
        return "file:" + relative(file);
    }

    private String relative(Path file) {
        return repositoryRoot.relativize(file.toAbsolutePath().normalize()).toString();
    }

    private String simpleName(String id) {
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    private String packageFor(String typeOrMethodId) {
        String ownerId = typeOrMethodId;
        int methodSeparator = ownerId.indexOf(".<init>(");
        if (methodSeparator < 0) {
            methodSeparator = ownerId.indexOf('(');
            if (methodSeparator >= 0) {
                int methodDot = ownerId.lastIndexOf('.', methodSeparator);
                ownerId = methodDot >= 0 ? ownerId.substring(0, methodDot) : ownerId;
            }
        } else {
            ownerId = ownerId.substring(0, methodSeparator);
        }
        TypeInfo type = typesById.get(ownerId);
        if (type != null) {
            return type.packageName();
        }
        int dot = ownerId.lastIndexOf('.');
        return dot >= 0 ? ownerId.substring(0, dot) : "";
    }

    private int startLine(com.github.javaparser.ast.Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(0);
    }

    private int endLine(com.github.javaparser.ast.Node node) {
        return node.getRange().map(range -> range.end.line).orElse(0);
    }

    private int fileEndLine(Path file) {
        try {
            return Files.readAllLines(file).size();
        } catch (IOException exception) {
            return 0;
        }
    }

    private record MethodInfo(
            String id,
            String methodName,
            String ownerTypeId,
            String ownerSimpleName,
            String signature,
            String visibility,
            String returnType,
            List<String> parameterTypes,
            String sourceFile,
            int startLine,
            int endLine
    ) {
    }

    private static final class TypeInfo {
        private final String id;
        private final String simpleName;
        private final String packageName;
        private final String sourceFile;
        private final Map<String, String> fields = new HashMap<>();
        private final List<String> interfaceIds = new ArrayList<>();
        private String superClassId = "";

        private TypeInfo(String id, String simpleName, String packageName, String sourceFile) {
            this.id = id;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.sourceFile = sourceFile;
        }

        String id() {
            return id;
        }

        String simpleName() {
            return simpleName;
        }

        String packageName() {
            return packageName;
        }

        String sourceFile() {
            return sourceFile;
        }

        Map<String, String> fields() {
            return fields;
        }

        List<String> interfaceIds() {
            return interfaceIds;
        }

        String superClassId() {
            return superClassId;
        }

        void superClassId(String superClassId) {
            this.superClassId = superClassId;
        }
    }
}
