from typing import Dict, Any, List
import javalang
from java_type_converter import convert_type 

def extract_methods(methods: List[javalang.tree.MethodDeclaration]) -> List[Dict[str, Any]]:
    return [{
        "name": method.name,
        "return_type": convert_type(method.return_type) if method.return_type else "void",
        "parameters": extract_parameters(method.parameters),
        "modifiers": list(method.modifiers),
        "throws": [exception for exception in method.throws] if method.throws else [],
        "body": extract_method_body(method.body) if method.body else None
    } for method in methods]

def extract_constructors(constructors: List[javalang.tree.ConstructorDeclaration]) -> List[Dict[str, Any]]:
    return [{
        "name": constructor.name,
        "parameters": extract_parameters(constructor.parameters),
        "modifiers": list(constructor.modifiers),
        "throws": [exception for exception in constructor.throws] if constructor.throws else [],
        "body": extract_method_body(constructor.body) if constructor.body else None
    } for constructor in constructors]

def extract_parameters(parameters: List[javalang.tree.FormalParameter]) -> List[Dict[str, str]]:
    return [{
        "name": param.name,
        "type": convert_type(param.type)
    } for param in parameters]

def extract_method_body(body):
    statements = []
    for statement in body:
        if isinstance(statement, javalang.tree.StatementExpression):
            if isinstance(statement.expression, javalang.tree.MethodInvocation):
                if statement.expression.member == 'println' and statement.expression.qualifier == 'System.out':
                    statements.append("System.out.println call")
                else:
                    statements.append(f"Method call: {statement.expression.member}")
            elif isinstance(statement.expression, javalang.tree.Assignment):
                statements.append("Assignment operation")
        elif isinstance(statement, javalang.tree.ReturnStatement):
            statements.append("Return statement")
        # Add more statement types as needed
    return statements if statements else "Empty method body"

def get_visibility(modifiers):
    visibility_modifiers = ['public', 'protected', 'private']
    for modifier in modifiers:
        if modifier in visibility_modifiers:
            return modifier
    return 'package-private'  # default visibility in Java

# These functions are kept for backwards compatibility
def extract_class_info(node: javalang.tree.ClassDeclaration) -> Dict[str, Any]:
    return {
        "name": node.name,
        "methods": extract_methods(node.methods),
        "constructors": extract_constructors(node.constructors),
        "modifiers": list(node.modifiers)
    }

def extract_interface_info(node: javalang.tree.InterfaceDeclaration) -> Dict[str, Any]:
    return {
        "name": node.name,
        "methods": extract_methods(node.methods),
        "modifiers": list(node.modifiers)
    }

def extract_enum_info(node: javalang.tree.EnumDeclaration) -> Dict[str, Any]:
    return {
        "name": node.name,
        "constants": [const.name for const in node.body.constants],
        "methods": extract_methods(node.methods),
        "modifiers": list(node.modifiers)
    }