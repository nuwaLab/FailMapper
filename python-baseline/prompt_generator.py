import json
import os
import re
import argparse

def load_json(file_path):
    with open(file_path, 'r') as file:
        return json.load(file)

def parse_complex_type(type_str):
    if isinstance(type_str, str):
        return type_str
    elif isinstance(type_str, dict):
        return type_str.get('name', str(type_str))
    elif hasattr(type_str, 'name'):
        if type_str.name == 'List' and hasattr(type_str, 'arguments') and type_str.arguments:
            inner_type = parse_complex_type(type_str.arguments[0].type)
            return f"List<{inner_type}>"
        elif hasattr(type_str, 'arguments') and type_str.arguments:
            args = ', '.join(parse_complex_type(arg.type) for arg in type_str.arguments)
            return f"{type_str.name}<{args}>"
        else:
            return type_str.name
    else:
        return str(type_str)

def get_methods(class_info):
    return set(method['name'] for method in class_info.get('methods', []))

def get_imports_from_methods(methods):
    imports = set()
    for method in methods:
        imports.add(parse_complex_type(method['return_type']))
        for param in method.get('parameters', []):
            imports.add(parse_complex_type(param))
    return imports

def get_imports_from_fields(fields):
    return set(parse_complex_type(field['type']) for field in fields)

def summarize_data_flow(class_info):
    summary = {
        "field_initializations": [],
        "method_flows": {},
        "overall_flow": [],
        "boundary_conditions": [],
        "exception_handling": []
    }

    for field in class_info.get("fields", []):
        if "initializer" in field:
            summary["field_initializations"].append(f"{field['name']} = {field['initializer']}")

    data_flow_graph = class_info.get("data_flow_graph", {})
    for method, flow in data_flow_graph.items():
        method_summary = []
        for step in flow:
            if step["type"] == "condition":
                condition = step.get('to', '')
                method_summary.append(f"Check: {condition}")
                summary["boundary_conditions"].append(f"{method}: {condition}")
            elif step["type"] == "assignment":
                method_summary.append(f"Assign: {step.get('details', '')}")
            elif step["type"] == "throw":
                exception = step.get('from', '')
                method_summary.append(f"Throw: {exception}")
                summary["exception_handling"].append(f"{method}: Throws {exception}")
            elif step["type"] == "return":
                method_summary.append(f"Return: {step.get('details', '')}")
        summary["method_flows"][method] = method_summary

    for field_init in summary["field_initializations"]:
        summary["overall_flow"].append(f"Initialize: {field_init}")
    for method, flow in summary["method_flows"].items():
        summary["overall_flow"].append(f"Method: {method}")
        summary["overall_flow"].extend(f"  {step}" for step in flow)

    return summary

# -------- 新增：依赖API解析与注入 --------

def _build_simple_to_fqn_map(testable_units):
    simple_to_fqns = {}
    for fqn, info in testable_units.items():
        name = info.get('class_name') or fqn.split('.')[-1]
        simple_to_fqns.setdefault(name, []).append(fqn)
    return simple_to_fqns

def _resolve_dep_fqns(import_types, package, testable_units, indirect_deps_for_class):
    """将类型名解析为FQN，优先：
    1) 已是FQN且存在于testable_units
    2) simpleName与indirect_deps匹配
    3) simpleName在testable_units唯一匹配
    多个候选则全部保留（由LLM在上下文中参考，不做臆造）。
    """
    resolved = set()
    simple_to_fqns = _build_simple_to_fqn_map(testable_units)

    for t in import_types:
        if not t or t in {"void", "boolean", "int", "long", "float", "double", "char", "byte", "short"}:
            continue
        if '.' in t and t in testable_units:
            resolved.add(t)
            continue
        # simple name 解析
        candidates = simple_to_fqns.get(t, [])
        if indirect_deps_for_class:
            # 与间接依赖交集优先
            matched = [c for c in candidates if c in indirect_deps_for_class]
            if matched:
                resolved.update(matched)
                continue
        if candidates:
            resolved.update(candidates)
    return list(resolved)

def _summarize_dep_api(fqn, info):
    summary = {
        "name": fqn,
        "package": info.get('package'),
        "type": info.get('type'),
        "superclass": info.get('superclass') or (info.get('extends') if isinstance(info.get('extends'), str) else None),
        "interfaces": info.get('interfaces') or info.get('extends') or [],
        "fields": info.get('fields', []),
        "methods": []
    }
    for m in info.get('methods', []):
        summary["methods"].append({
            "name": m.get('name'),
            "parameters": m.get('parameters', []),
            "return_type": m.get('return_type', 'void')
        })
    return summary

# ---------------------------------------

def generate_prompt(class_info, package, dependencies_info, indirect_dependencies_info):
    class_name = class_info['name']
    
    superclass = class_info.get('extends')
    implemented_interfaces = class_info.get('implements', [])
    
    fields = [
        {
            "name": field["name"],
            "type": parse_complex_type(field["type"]),
            "modifiers": field["modifiers"],
            "initializer": field.get("initializer", "None"),
            "visibility": field.get("visibility", "package-private"),
            "is_final": "final" in field["modifiers"]
        }
        for field in class_info.get('fields', [])
    ]
    
    constructors = [
        {
            "name": constructor["name"],
            "parameters": [parse_complex_type(param) for param in constructor.get("parameters", [])],
            "modifiers": constructor["modifiers"],
            "throws": [parse_complex_type(exception) for exception in constructor.get("throws", [])],
            "body": constructor.get("body", "Not available")
        }
        for constructor in class_info.get('constructors', [])
    ]
    
    inherited_methods = set()
    if superclass:
        superclass_info = dependencies_info['testable_units'].get(superclass, {})
        inherited_methods.update(get_methods(superclass_info))
    for interface in implemented_interfaces:
        interface_info = dependencies_info['testable_units'].get(interface, {})
        inherited_methods.update(get_methods(interface_info))
    
    methods = [
        {
            "name": method["name"],
            "return_type": parse_complex_type(method["return_type"]),
            "parameters": [parse_complex_type(param) for param in method.get("parameters", [])],
            "modifiers": method["modifiers"],
            "throws": [parse_complex_type(exception) for exception in method.get("throws", [])],
            "body": method.get("body", "Not available"),
            "is_override": method["name"] in inherited_methods
        }
        for method in class_info.get('methods', [])
    ]

    data_flow_summary = summarize_data_flow(class_info)
    
    direct_deps = dependencies_info.get('dependencies', [])
    indirect_deps = indirect_dependencies_info.get(f"{package}.{class_name}", [])

    imports = set()
    imports.add(f"{package}.{class_name}")
    imports.update(implemented_interfaces)
    if superclass:
        imports.add(superclass)
    imports.update(get_imports_from_methods(methods))
    imports.update(get_imports_from_fields(fields))
    imports.update(class_info.get('imports', []))

    # Remove basic types from imports
    basic_types = {"void", "boolean", "int", "long", "float", "double", "char", "byte", "short"}
    imports = {imp for imp in imports if imp not in basic_types}

    is_generic = '<' in class_name or any('<' in str(field['type']) for field in fields) or any('<' in str(method['return_type']) for method in methods)

    # ------- 新增：解析并汇总依赖类型API -------
    testable_units = dependencies_info.get('testable_units', {})
    # 从 imports 中提取简单类型名（去掉可能的包名前缀与泛型后缀）
    simple_import_types = set()
    for imp in imports:
        name = str(imp)
        name = name.split('.')[-1]
        name = re.sub(r"<.*>", "", name)
        simple_import_types.add(name)

    resolved_dep_fqns = _resolve_dep_fqns(simple_import_types, package, testable_units, set(indirect_deps))

    dependency_api_refs = []
    for fqn in resolved_dep_fqns[:20]:  # 避免提示过长，最多20个类型
        info = testable_units.get(fqn)
        if not info:
            continue
        dependency_api_refs.append(_summarize_dep_api(fqn, info))

    unresolved_types = sorted(list(simple_import_types - set([f.split('.')[-1] for f in resolved_dep_fqns])))
    # -----------------------------------------

    prompt = f"""
===============================
JAVA CLASS UNIT TEST GENERATION
===============================

Class: {class_name}
Package: {package}

CRITICAL TESTING REQUIREMENTS:
1. DO NOT use any mocking frameworks (Mockito, EasyMock, PowerMock, etc.)
2. DO NOT use @Mock, @MockBean, @InjectMocks, or any mock-related annotations
3. DO NOT import any mocking libraries (org.mockito.*, static imports from Mockito, etc.)
4. Use only real objects and direct instantiation for testing
5. For dependencies, create real instances or use test doubles without mocking frameworks
6. Focus on testing actual behavior with real object interactions

-----------
1. STRUCTURE
-----------
Superclass: {superclass if superclass else 'None'}
Implemented Interfaces: {', '.join(implemented_interfaces) if implemented_interfaces else 'None'}

Fields:
{json.dumps(fields, indent=4)}

Constructors:
{json.dumps(constructors, indent=4)}

Methods:
{json.dumps(methods, indent=4)}

--------------------
2. DATA FLOW SUMMARY
--------------------
Field Initializations:
{json.dumps(data_flow_summary['field_initializations'], indent=4)}

Method Flows:
{json.dumps(data_flow_summary['method_flows'], indent=4)}

Overall Flow:
{json.dumps(data_flow_summary['overall_flow'], indent=4)}

Boundary Conditions:
{json.dumps(data_flow_summary['boundary_conditions'], indent=4)}

Exception Handling:
{json.dumps(data_flow_summary['exception_handling'], indent=4)}

-------------
3. DEPENDENCIES
-------------
Direct Dependencies:
{json.dumps(direct_deps, indent=4)}

Indirect Dependencies:
{json.dumps(indirect_deps, indent=4)}

Imports:
{json.dumps(list(imports), indent=4)}

------------------------------
4. DEPENDENCY API REFERENCES
------------------------------
(Only use the following APIs for collaborators; do NOT fabricate missing members.)
{json.dumps(dependency_api_refs, indent=4)}

Unresolved External Types (treat as opaque; do NOT implement or cast to them):
{json.dumps(unresolved_types, indent=4)}

-----------
5. GUIDELINES
-----------
- Never invent methods/fields on dependency types. Only call methods listed under DEPENDENCY API REFERENCES.
- If a needed method is missing, adapt the test to use available public APIs or construct minimal real instances.
- Do NOT implement/extend the wrong interface/class; ensure generics and type bounds strictly match the API summaries.

"""
    
    return prompt


def process_project(json_file, output_dir):
    data = load_json(json_file)
    
    dfg_info = data['data_flow_graph']
    dependencies_info = data['dependencies']
    indirect_dependencies_info = data['indirect_dependencies']
    
    os.makedirs(output_dir, exist_ok=True)

    generated_files = {}
    for file_path, file_info in dfg_info.items():
        for class_info in file_info.get('classes', []):
            class_name = class_info['name']
            # dfg keys are project-relative paths while testable_units store the
            # analyzed path; prefer the unit from the same source file so that
            # same-named classes in different packages resolve correctly
            candidates = [info for info in dependencies_info['testable_units'].values()
                          if info['class_name'] == class_name]
            same_file = [info for info in candidates
                         if info.get('file_path', '') == file_path
                         or info.get('file_path', '').endswith(os.sep + file_path)]
            if same_file:
                package = same_file[0]['package']
            elif candidates:
                package = candidates[0]['package']
            else:
                package = ""

            prompt = generate_prompt(class_info, package, dependencies_info, indirect_dependencies_info)

            output_file = os.path.join(output_dir, f"{class_name}_test_prompt.txt")
            if class_name in generated_files:
                print(f"WARNING: prompt for class '{class_name}' already generated from "
                      f"{generated_files[class_name]}; overwriting with {file_path}. "
                      f"Same-named classes share one prompt file.")
            generated_files[class_name] = file_path
            with open(output_file, 'w') as f:
                f.write(prompt)

            print(f"Generated test prompt for {package}.{class_name}")

def main():
    parser = argparse.ArgumentParser(description="Generate test prompts from static analysis results.")
    parser.add_argument("json_file", help="Path to the combined analysis JSON file")
    parser.add_argument("--output_dir", default="test_prompts", help="Directory to save generated prompts")
    args = parser.parse_args()

    process_project(args.json_file, args.output_dir)

if __name__ == "__main__":
    main()