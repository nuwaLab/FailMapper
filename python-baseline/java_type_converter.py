import javalang

def convert_reference_type(ref_type):
    if ref_type is None:
        return ""
    if not isinstance(ref_type, javalang.tree.ReferenceType):
        return str(ref_type)
    
    base_type = ref_type.name
    
    if ref_type.arguments:
        arg_types = [convert_reference_type(arg.type) for arg in ref_type.arguments]
        base_type += f"<{', '.join(arg_types)}>"
    
    if ref_type.dimensions:
        base_type += "[]" * len(ref_type.dimensions)
    
    return base_type

def convert_type(type_obj):
    if type_obj is None:
        return ""
    if isinstance(type_obj, javalang.tree.ReferenceType):
        return convert_reference_type(type_obj)
    elif isinstance(type_obj, javalang.tree.BasicType):
        return type_obj.name
    elif isinstance(type_obj, str):
        return type_obj
    else:
        return str(type_obj)