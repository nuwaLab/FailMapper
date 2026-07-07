import json
from typing import Dict, List, Any

def summarize_data_flow(class_info: Dict[str, Any]) -> Dict[str, Any]:
    summary = {
        "class_name": class_info["name"],
        "field_initializations": [],
        "method_flows": {},
        "overall_flow": [],
        "boundary_conditions": [],
        "exception_handling": []
    }

    # Summarize field initializations
    for field in class_info["fields"]:
        if field["initializer"]:
            summary["field_initializations"].append({
                "field": field["name"],
                "initial_value": field["initializer"],
                "type": field["type"]
            })
            summary["overall_flow"].append(f"Initialize {field['name']} = {field['initializer']}")

    # Summarize method flows
    for method in class_info["methods"]:
        method_name = method["name"]
        method_flow = class_info["data_flow_graph"].get(method_name, [])
        summary["method_flows"][method_name] = method_flow

        # Add method flow to overall flow and identify boundary conditions and exceptions
        summary["overall_flow"].append(f"Method: {method_name}")
        for flow in method_flow:
            if flow["type"] == "condition":
                summary["overall_flow"].append(f"  Check: {flow['to']}")
                summary["boundary_conditions"].append(f"{method_name}: {flow['to']}")
            elif flow["type"] == "assignment":
                details = flow.get("details", "")
                if details:
                    summary["overall_flow"].append(f"  Assign: {details}")
                else:
                    summary["overall_flow"].append(f"  Assign: {', '.join(flow['from'])} -> {', '.join(flow['to'])}")
            elif flow["type"] == "throw":
                summary["overall_flow"].append(f"  Throw: {flow['from']}")
                summary["exception_handling"].append(f"{method_name}: Throws {flow['from']}")
            elif flow["type"] == "return":
                details = flow.get("details", "")
                if details:
                    summary["overall_flow"].append(f"  Return: {details}")
                else:
                    summary["overall_flow"].append(f"  Return: {', '.join(flow['from'])}")

    # Add field relationships to overall flow
    for field, related_fields in class_info["field_relationships"].items():
        if related_fields:
            summary["overall_flow"].append(f"Field {field} depends on: {', '.join(related_fields)}")

    return summary

def print_summary(summary: Dict[str, Any]):
    print(f"Class: {summary['class_name']}")
    print("\nField Initializations:")
    for init in summary["field_initializations"]:
        print(f"  {init['field']} ({init['type']}): {init['initial_value']}")

    print("\nMethod Flows:")
    for method, flow in summary["method_flows"].items():
        print(f"  {method}:")
        for step in flow:
            if step["type"] == "condition":
                print(f"    Check: {step['to']}")
            elif step["type"] == "assignment":
                details = step.get("details", "")
                if details:
                    print(f"    Assign: {details}")
                else:
                    print(f"    Assign: {', '.join(step['from'])} -> {', '.join(step['to'])}")
            elif step["type"] == "throw":
                print(f"    Throw: {step['from']}")
            elif step["type"] == "return":
                details = step.get("details", "")
                if details:
                    print(f"    Return: {details}")
                else:
                    print(f"    Return: {', '.join(step['from'])}")

    print("\nOverall Data Flow:")
    for step in summary["overall_flow"]:
        print(f"  {step}")

    print("\nBoundary Conditions:")
    for condition in summary["boundary_conditions"]:
        print(f"  {condition}")

    print("\nException Handling:")
    for exception in summary["exception_handling"]:
        print(f"  {exception}")

def main():
    # project_name = "Tutorial_Stack"
    # input_file = f"/home/ricky/Desktop/unit_test/results/static_analysis/{project_name}_dfg.json"
    project_name = "calculator"
    input_file = f"/home/ricky/Desktop/unit_test/java_unit_test/output/calculator/analysis/calculator/calculator_dfg.json"
    
    # 读取JSON文件
    with open(input_file, 'r') as f:
        data = json.load(f)
    
    # 假设JSON文件包含多个类的信息，我们遍历每个类
    for file_path, file_info in data.items():
        for class_info in file_info.get("classes", []):
            summary = summarize_data_flow(class_info)
            print(f"\nAnalysis for file: {file_path}")
            print_summary(summary)
            print("\n" + "="*50 + "\n")

if __name__ == "__main__":
    main()