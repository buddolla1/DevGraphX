package com.devgraphx.core.model;

import java.util.List;

public record CodeNode(
        String id,
        String label,
        String kind,
        String sourceFile,
        int startLine,
        int endLine,
        String methodName,
        String owningClass,
        String fullyQualifiedClassName,
        String signature,
        String visibility,
        String returnType,
        List<String> parameterTypes
) {
    public CodeNode(String id, String label, String kind, String sourceFile, int startLine, int endLine) {
        this(id, label, kind, sourceFile, startLine, endLine, "", "", "", "", "", "", List.of());
    }

    public CodeNode {
        methodName = methodName == null ? "" : methodName;
        owningClass = owningClass == null ? "" : owningClass;
        fullyQualifiedClassName = fullyQualifiedClassName == null ? "" : fullyQualifiedClassName;
        signature = signature == null ? "" : signature;
        visibility = visibility == null ? "" : visibility;
        returnType = returnType == null ? "" : returnType;
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
    }

    public int line() {
        return startLine;
    }

    public String location() {
        if (sourceFile == null || sourceFile.isBlank()) {
            return "";
        }
        if (startLine <= 0) {
            return sourceFile;
        }
        if (endLine > startLine) {
            return sourceFile + ":" + startLine + "-" + endLine;
        }
        return sourceFile + ":" + startLine;
    }
}
