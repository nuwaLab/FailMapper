from typing import Dict, Any, List
import javalang
from method_analyzer import extract_methods, extract_constructors, get_visibility
from java_type_converter import convert_type

def extract_class_info(node: javalang.tree.ClassDeclaration) -> Dict[str, Any]:
    return {
        "name": node.name,
        "fields": extract_fields(node.fields),
        "methods": extract_methods(node.methods),
        "constructors": extract_constructors(node.constructors),
        "field_relationships": extract_field_relationships(node.fields),
        "modifiers": list(node.modifiers),
        "extends": node.extends.name if node.extends else None,
        "implements": [interface.name for interface in node.implements] if node.implements else []
    }

def extract_fields(fields: List[javalang.tree.FieldDeclaration]) -> List[Dict[str, Any]]:
    field_info = []
    for field in fields:
        for decl in field.declarators:
            field_data = {
                "name": decl.name,
                "type": convert_type(field.type),
                "modifiers": list(field.modifiers),
                "initializer": extract_initializer(decl.initializer),
                "is_final": "final" in field.modifiers,
                "visibility": get_visibility(field.modifiers)
            }
            field_info.append(field_data)
    return field_info

def extract_initializer(initializer):
    if initializer is None:
        return None
    if isinstance(initializer, javalang.tree.Literal):
        return initializer.value
    elif isinstance(initializer, javalang.tree.BinaryOperation):
        return f"{extract_initializer(initializer.operandl)} {initializer.operator} {extract_initializer(initializer.operandr)}"
    elif isinstance(initializer, javalang.tree.MethodInvocation):
        args = ", ".join(extract_initializer(arg) if extract_initializer(arg) is not None else "" for arg in initializer.arguments)
        return f"{initializer.member}({args})"
    elif isinstance(initializer, javalang.tree.ClassCreator):
        args = ", ".join(extract_initializer(arg) if extract_initializer(arg) is not None else "" for arg in initializer.arguments)
        return f"new {convert_type(initializer.type)}({args})"
    elif isinstance(initializer, javalang.tree.Cast):
        return f"({convert_type(initializer.type)}) {extract_initializer(initializer.expression)}"
    elif isinstance(initializer, javalang.tree.ArrayCreator):
        # Handle None values in dimensions
        safe_dimensions = []
        for dim in initializer.dimensions:
            dim_value = extract_initializer(dim)
            if dim_value is not None:
                safe_dimensions.append(dim_value)
            else:
                safe_dimensions.append("")
        
        dimensions = ", ".join(safe_dimensions)
        type_name = convert_type(initializer.type)
        return f"new {type_name}[{dimensions}]"
    elif isinstance(initializer, javalang.tree.MemberReference):
        return initializer.member
    elif isinstance(initializer, javalang.tree.This):
        return "this"
    elif isinstance(initializer, javalang.tree.VariableDeclarator):
        return extract_initializer(initializer.initializer)
    return str(initializer)

def extract_field_relationships(fields: List[javalang.tree.FieldDeclaration]) -> Dict[str, List[str]]:
    relationships = {}
    field_names = set()
    
    for field in fields:
        for decl in field.declarators:
            field_names.add(decl.name)
    
    for field in fields:
        for decl in field.declarators:
            related_fields = []
            if decl.initializer:
                initializer_str = extract_initializer(decl.initializer)
                for field_name in field_names:
                    if field_name in initializer_str:
                        related_fields.append(field_name)
            relationships[decl.name] = related_fields
    
    return relationships

def analyze_class(tree: javalang.tree.CompilationUnit) -> List[Dict[str, Any]]:
    class_infos = []
    for _, node in tree.filter(javalang.tree.ClassDeclaration):
        class_info = extract_class_info(node)
        class_infos.append(class_info)
    return class_infos