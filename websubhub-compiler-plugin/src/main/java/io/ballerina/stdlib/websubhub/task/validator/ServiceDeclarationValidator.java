package io.ballerina.stdlib.websubhub.task.validator;

import io.ballerina.compiler.api.ModuleID;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.Qualifier;
import io.ballerina.compiler.api.symbols.ServiceDeclarationSymbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.NodeLocation;
import io.ballerina.compiler.syntax.tree.ParenthesizedArgList;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.stdlib.websubhub.Constants;
import io.ballerina.stdlib.websubhub.WebSubHubDiagnosticCodes;
import io.ballerina.stdlib.websubhub.task.visitor.ListenerInitiationExpressionVisitor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.ballerina.stdlib.websubhub.task.AnalyserUtils.updateContext;

/**
 * {@code ServiceDeclarationValidator} validates whether websubhub service declaration is complying to current websubhub
 * package implementation.
 */
public class ServiceDeclarationValidator {
    private static final ServiceDeclarationValidator INSTANCE = new ServiceDeclarationValidator();
    private static final List<String> allowedMethods;
    private static final List<String> requiredMethods;
    private static final Map<String, List<String>> allowedParameterTypes;
    private static final Map<String, List<String>> allowedReturnTypes;
    private static final List<String> methodsWithOptionalReturnTypes;

    static {
        allowedMethods = Collections.emptyList();
        requiredMethods = Collections.emptyList();
        allowedParameterTypes = Collections.emptyMap();
        allowedReturnTypes = Collections.emptyMap();
        methodsWithOptionalReturnTypes = Collections.emptyList();
    }

    public static ServiceDeclarationValidator getInstance() {
        return INSTANCE;
    }

    public void validate(SyntaxNodeAnalysisContext context, ServiceDeclarationNode serviceNode,
                         ListenerInitiationExpressionVisitor visitor,
                         ServiceDeclarationSymbol serviceDeclarationSymbol) {
        validateListenerArguments(context, visitor);
        List<FunctionDefinitionNode> availableFunctionDeclarations = serviceNode.members().stream()
                .filter(member -> member.kind() == SyntaxKind.OBJECT_METHOD_DEFINITION)
                .map(member -> (FunctionDefinitionNode) member).collect(Collectors.toList());
        validateRequiredMethodsImplemented(context, availableFunctionDeclarations, serviceNode.location());
        availableFunctionDeclarations.forEach(fd -> {
            context.semanticModel().symbol(fd).ifPresent(fs -> {
                NodeLocation location = fd.location();
                validateRemoteQualifier(context, (FunctionSymbol) fs, location);
                validateAdditionalMethodImplemented(context, fd, location);
                validateMethodParameters(context, fd, ((FunctionSymbol) fs).typeDescriptor());
                validateMethodReturnTypes(context, fd, ((FunctionSymbol) fs).typeDescriptor());
            });
        });
    }

    private void validateListenerArguments(SyntaxNodeAnalysisContext context,
                                          ListenerInitiationExpressionVisitor visitor) {
        visitor.getExplicitNewExpressionNodes()
                .forEach(explicitNewExpressionNode -> {
                    SeparatedNodeList<FunctionArgumentNode> functionArgs = explicitNewExpressionNode
                            .parenthesizedArgList().arguments();
                    verifyListenerArgType(context, explicitNewExpressionNode.location(), functionArgs);
                });

        visitor.getImplicitNewExpressionNodes()
                .forEach(implicitNewExpressionNode -> {
                    Optional<ParenthesizedArgList> argListOpt = implicitNewExpressionNode.parenthesizedArgList();
                    if (argListOpt.isPresent()) {
                        SeparatedNodeList<FunctionArgumentNode> functionArgs = argListOpt.get().arguments();
                        verifyListenerArgType(context, implicitNewExpressionNode.location(), functionArgs);
                    }
                });
    }

    private void verifyListenerArgType(SyntaxNodeAnalysisContext context, NodeLocation location,
                                       SeparatedNodeList<FunctionArgumentNode> functionArgs) {
        if (functionArgs.size() >= 2) {
            PositionalArgumentNode firstArg = (PositionalArgumentNode) functionArgs.get(0);
            PositionalArgumentNode secondArg = (PositionalArgumentNode) functionArgs.get(1);
            SyntaxKind firstArgSyntaxKind = firstArg.expression().kind();
            SyntaxKind secondArgSyntaxKind = secondArg.expression().kind();
            if ((firstArgSyntaxKind == SyntaxKind.SIMPLE_NAME_REFERENCE
                    || firstArgSyntaxKind == SyntaxKind.MAPPING_CONSTRUCTOR)
                    && (secondArgSyntaxKind == SyntaxKind.SIMPLE_NAME_REFERENCE
                    || secondArgSyntaxKind == SyntaxKind.MAPPING_CONSTRUCTOR)) {
                WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_101;
                updateContext(context, errorCode, location);
            }
        }
    }

    private void validateRequiredMethodsImplemented(SyntaxNodeAnalysisContext context,
                                                    List<FunctionDefinitionNode> availableFunctionDeclarations,
                                                    NodeLocation location) {
        List<String> availableMethods = availableFunctionDeclarations.stream()
                .map(fd -> fd.functionName().toString()).collect(Collectors.toList());
        boolean requiredMethodsImplemented = availableMethods.containsAll(requiredMethods);
        if (!requiredMethodsImplemented) {
            WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_103;
            String requiredMethodsMsg = String.join(",", requiredMethods);
            updateContext(context, errorCode, location, requiredMethodsMsg);
        }
    }

    private void validateRemoteQualifier(SyntaxNodeAnalysisContext context, FunctionSymbol functionSymbol,
                                         NodeLocation location) {
        boolean containsRemoteQualifier = functionSymbol.qualifiers().contains(Qualifier.REMOTE);
        if (!containsRemoteQualifier) {
            WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_102;
            updateContext(context, errorCode, location);
        }
    }

    private void validateAdditionalMethodImplemented(SyntaxNodeAnalysisContext context,
                                                     FunctionDefinitionNode functionDefinition, NodeLocation location) {
        String functionName = functionDefinition.functionName().toString();
        if (!allowedMethods.contains(functionName)) {
            WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_104;
            updateContext(context, errorCode, location, functionName);
        }
    }

    private void validateMethodParameters(SyntaxNodeAnalysisContext context, FunctionDefinitionNode functionDefinition,
                                          FunctionTypeSymbol typeSymbol) {
        String functionName = functionDefinition.functionName().toString();
        if (allowedMethods.contains(functionName)) {
            List<String> allowedParameters = allowedParameterTypes.get(functionName);
            List<List<String>> allowedParams = allowedParameters.stream()
                    .map(param -> Arrays.stream(param.trim().split("\\|"))
                            .map(String::trim).collect(Collectors.toList())
                    ).collect(Collectors.toList());
            Optional<List<ParameterSymbol>> paramOpt = typeSymbol.params();
            if (paramOpt.isPresent()) {
                List<ParameterSymbol> params = paramOpt.get();
                if (params.isEmpty()) {
                    if (!allowedParameters.isEmpty()) {
                        WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_106;
                        updateContext(context, errorCode, functionDefinition.location(), functionName,
                                String.join("", allowedParameters));
                    }
                } else {
                    Optional<String> errorMsg = params.stream()
                            .filter(p -> isParamNotAllowed(allowedParams, p.typeDescriptor()))
                            .map(e -> getInvalidTypeDescription(e.typeDescriptor()))
                            .reduce(String::join);
                    if (errorMsg.isPresent()) {
                        String message = errorMsg.get();
                        WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_105;
                        updateContext(context, errorCode, functionDefinition.location(), message, functionName);
                    }
                }
            } else {
                if (!allowedParameters.isEmpty()) {
                    WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_106;
                    updateContext(context, errorCode, functionDefinition.location(), functionName,
                            String.join("", allowedParameters));
                }
            }
        }
    }

    private String getInvalidTypeDescription(TypeSymbol paramType) {
        TypeDescKind paramKind = paramType.typeKind();
        if (TypeDescKind.TYPE_REFERENCE.equals(paramKind)) {
            String moduleName = paramType.getModule().flatMap(ModuleSymbol::getName).orElse("");
            String type = paramType.getName().orElse("");
            return getQualifiedType(type, moduleName);
        } else if (TypeDescKind.UNION.equals(paramKind)) {
            return ((UnionTypeSymbol) paramType)
                    .memberTypeDescriptors().stream()
                    .map(this::getInvalidTypeDescription)
                    .filter(e -> !e.isEmpty() && !e.isBlank())
                    .reduce((a, b) -> String.join("|", a, b)).orElse("");
        } else if (TypeDescKind.ERROR.equals(paramKind)) {
            String signature = paramType.signature();
            Optional<ModuleID> moduleIdOpt = paramType.getModule().map(ModuleSymbol::id);
            String moduleId = moduleIdOpt.map(ModuleID::toString).orElse("");
            String type = signature.replace(moduleId, "").replace(":", "");
            String moduleName = moduleIdOpt.map(ModuleID::modulePrefix).orElse("");
            return getQualifiedType(type, moduleName);
        } else {
            return paramType.getName().orElse("");
        }
    }

    private boolean isParamNotAllowed(List<List<String>> allowedParams, TypeSymbol paramTypeDescriptor) {
        TypeDescKind typeKind = paramTypeDescriptor.typeKind();
        if (TypeDescKind.UNION.equals(typeKind)) {
            return ((UnionTypeSymbol) paramTypeDescriptor)
                    .memberTypeDescriptors().stream()
                    .map(e -> isParamNotAllowed(allowedParams, e))
                    .reduce(false, (a , b) -> a || b);
        } else if (TypeDescKind.TYPE_REFERENCE.equals(typeKind)) {
            String moduleName = paramTypeDescriptor.getModule().flatMap(ModuleSymbol::getName).orElse("");
            String paramType = paramTypeDescriptor.getName().orElse("");
            String qualifiedParamType = getQualifiedType(paramType, moduleName);
            return allowedParams.stream().noneMatch(p -> p.contains(qualifiedParamType));
        } else if (TypeDescKind.ERROR.equals(typeKind)) {
            String signature = paramTypeDescriptor.signature();
            Optional<ModuleID> moduleIdOpt = paramTypeDescriptor.getModule().map(ModuleSymbol::id);
            String moduleId = moduleIdOpt.map(ModuleID::toString).orElse("");
            String paramType = signature.replace(moduleId, "").replace(":", "");
            String moduleName = moduleIdOpt.map(ModuleID::modulePrefix).orElse("");
            String qualifiedParamType = getQualifiedType(paramType, moduleName);
            return allowedParams.stream().noneMatch(p -> p.contains(qualifiedParamType));
        } else {
            return true;
        }
    }

    private String getQualifiedType(String paramType, String moduleName) {
        return moduleName.isBlank() ? paramType : String.format("%s:%s", moduleName, paramType);
    }

    private void validateMethodReturnTypes(SyntaxNodeAnalysisContext context,
                                           FunctionDefinitionNode functionDefinition, FunctionTypeSymbol typeSymbol) {
        String functionName = functionDefinition.functionName().toString();
        List<String> predefinedReturnTypes = allowedReturnTypes.get(functionName);
        boolean nilableReturnTypeAllowed = methodsWithOptionalReturnTypes.contains(functionName);
        if (allowedMethods.contains(functionName)) {
            Optional<TypeSymbol> returnTypesOpt = typeSymbol.returnTypeDescriptor();
            if (returnTypesOpt.isPresent()) {
                TypeSymbol returnTypeDescription = returnTypesOpt.get();
                if (returnTypeDescription.typeKind().equals(TypeDescKind.NIL) && !nilableReturnTypeAllowed) {
                    WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_108;
                    updateContext(context, errorCode, functionDefinition.location(), functionName,
                            String.join("|", predefinedReturnTypes));
                    return;
                }

                boolean invalidReturnTypePresent = isReturnTypeNotAllowed(
                        predefinedReturnTypes, returnTypeDescription, nilableReturnTypeAllowed);
                if (invalidReturnTypePresent) {
                    String returnTypeName = getInvalidReturnTypeDescription(returnTypeDescription);
                    WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_107;
                    updateContext(context, errorCode, functionDefinition.location(), returnTypeName, functionName);
                }
            } else {
                if (!nilableReturnTypeAllowed) {
                    WebSubHubDiagnosticCodes errorCode = WebSubHubDiagnosticCodes.WEBSUBHUB_108;
                    updateContext(context, errorCode, functionDefinition.location(), functionName,
                            String.join("|", predefinedReturnTypes));
                }
            }
        }
    }

    private boolean isReturnTypeNotAllowed(List<String> allowedReturnTypes, TypeSymbol returnTypeDescriptor,
                                           boolean nilableReturnTypeAllowed) {
        TypeDescKind typeKind = returnTypeDescriptor.typeKind();
        if (TypeDescKind.UNION.equals(typeKind)) {
            return ((UnionTypeSymbol) returnTypeDescriptor)
                    .memberTypeDescriptors().stream()
                    .map(e -> isReturnTypeNotAllowed(allowedReturnTypes, e, nilableReturnTypeAllowed))
                    .reduce(false, (a , b) -> a || b);
        } else if (TypeDescKind.TYPE_REFERENCE.equals(typeKind)) {
            String moduleName = returnTypeDescriptor.getModule().flatMap(ModuleSymbol::getName).orElse("");
            String paramType = returnTypeDescriptor.getName().orElse("");
            String qualifiedParamType = getQualifiedType(paramType, moduleName);
            return !allowedReturnTypes.contains(qualifiedParamType);
        } else if (TypeDescKind.ERROR.equals(typeKind)) {
            String signature = returnTypeDescriptor.signature();
            Optional<ModuleID> moduleIdOpt = returnTypeDescriptor.getModule().map(ModuleSymbol::id);
            String moduleId = moduleIdOpt.map(ModuleID::toString).orElse("");
            String paramType = signature.replace(moduleId, "").replace(":", "");
            String moduleName = moduleIdOpt.map(ModuleID::modulePrefix).orElse("");
            String qualifiedParamType = getQualifiedType(paramType, moduleName);
            return !allowedReturnTypes.contains(qualifiedParamType);
        } else if (TypeDescKind.NIL.equals(typeKind)) {
            return !nilableReturnTypeAllowed;
        } else {
            return true;
        }
    }

    private String getInvalidReturnTypeDescription(TypeSymbol paramType) {
        TypeDescKind typeKind = paramType.typeKind();
        if (TypeDescKind.TYPE_REFERENCE.equals(typeKind)) {
            String moduleName = paramType.getModule().flatMap(ModuleSymbol::getName).orElse("");
            String type = paramType.getName().orElse("");
            return getQualifiedType(type, moduleName);
        } else if (TypeDescKind.UNION.equals(typeKind)) {
            List<TypeSymbol> availableTypes = ((UnionTypeSymbol) paramType).memberTypeDescriptors();
            boolean optionalSymbolAvailable = availableTypes.stream()
                    .anyMatch(t -> TypeDescKind.NIL.equals(t.typeKind()));
            List<String> typeDescriptions = availableTypes.stream()
                    .map(this::getInvalidReturnTypeDescription)
                    .filter(e -> !e.isEmpty() && !e.isBlank())
                    .collect(Collectors.toList());
            String concatenatedReturnTypes = String.join("|", typeDescriptions);
            return optionalSymbolAvailable ? concatenatedReturnTypes + Constants.OPTIONAL : concatenatedReturnTypes;
        } else if (TypeDescKind.ERROR.equals(typeKind)) {
            String signature = paramType.signature();
            Optional<ModuleID> moduleIdOpt = paramType.getModule().map(ModuleSymbol::id);
            String moduleId = moduleIdOpt.map(ModuleID::toString).orElse("");
            String type = signature.replace(moduleId, "").replace(":", "");
            String moduleName = moduleIdOpt.map(ModuleID::modulePrefix).orElse("");
            return getQualifiedType(type, moduleName);
        } else {
            return "";
        }
    }
}
