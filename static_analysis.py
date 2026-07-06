import os
import json
import argparse
import re
from file_analyzer import analyze_java_file
from dependency_analyzer import analyze_java_project
from indirect_dependency_analyzer import EnhancedJavaDependencyAnalyzer

class SetEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, set):
            return list(obj)
        return json.JSONEncoder.default(self, obj)

def clean_unicode_surrogates(text):
    """clean the Unicode surrogate characters in the string"""
    if isinstance(text, str):
        # remove the Unicode surrogate character range (\uD800-\uDFFF)
        cleaned = re.sub(r'[\uD800-\uDFFF]', '', text)
        return cleaned
    return text

def clean_data_recursive(data):
    """recursively clean the Unicode surrogate characters in the data structure"""
    if isinstance(data, dict):
        return {key: clean_data_recursive(value) for key, value in data.items()}
    elif isinstance(data, list):
        return [clean_data_recursive(item) for item in data]
    elif isinstance(data, set):
        return {clean_data_recursive(item) for item in data}
    elif isinstance(data, str):
        return clean_unicode_surrogates(data)
    else:
        return data

def analyze_project(project_path: str) -> dict:
    project_info = {}
    total_files = 0
    successful_files = 0
    failed_files = 0
    
    print(f"starting to analyze project: {project_path}")
    
    for root, dirs, files in os.walk(project_path):
        for file in files:
            if 'Test' in file:
                continue
            # Skip module-info.java files as they use Java 9+ module syntax which javalang can't parse
            if file == 'module-info.java':
                continue
            if file.endswith('.java'):
                total_files += 1
                file_path = os.path.join(root, file)
                relative_path = os.path.relpath(file_path, project_path)
                
                print(f"analyzing: {relative_path}")
                
                try:
                    result = analyze_java_file(file_path)
                    
                    # check if fallback method is used
                    if "parsing_method" in result:
                        if result["parsing_method"] == "regex_fallback":
                            print(f"  -> using regex fallback parsing")
                        elif result["parsing_method"] == "preprocessed_javalang":
                            print(f"  -> using preprocessed javalang parsing")
                        else:
                            print(f"  -> using {result['parsing_method']} parsing")
                    
                    project_info[relative_path] = result
                    successful_files += 1
                    
                except Exception as e:
                    failed_files += 1
                    print(f"  -> parsing failed: {str(e)}")
                    
                    # create the smallest error record
                    project_info[relative_path] = {
                        "error": str(e),
                        "classes": [],
                        "interfaces": [],
                        "enums": [],
                        "parsing_method": "failed"
                    }
    
    print(f"\nanalysis completed:")
    print(f"  total files: {total_files}")
    print(f"  successful parsing: {successful_files}")
    print(f"  parsing failed: {failed_files}")
    print(f"  success rate: {(successful_files/total_files*100):.1f}%" if total_files > 0 else "  success rate: 0%")
    
    return project_info

def save_json(data: dict, file_path: str):
    # clean the Unicode surrogate characters in the data
    cleaned_data = clean_data_recursive(data)
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(cleaned_data, f, ensure_ascii=False, indent=2, cls=SetEncoder)
    except UnicodeEncodeError as e:
        print(f"Unicode encoding error: {e}")
        print("Falling back to ASCII encoding...")
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(cleaned_data, f, ensure_ascii=True, indent=2, cls=SetEncoder)



def main():
    parser = argparse.ArgumentParser(description="Analyze Java project and generate static analysis results.")
    parser.add_argument("project_path", help="Path to the Java project")
    parser.add_argument("--output_dir", default="../results/static_analysis", help="Directory to save output files")
    args = parser.parse_args()

    project_name = os.path.basename(args.project_path)
    output_dir = os.path.join(args.output_dir, project_name)
    os.makedirs(output_dir, exist_ok=True)
    
    # file analysis (including data flow graph)
    output_file_dfg = os.path.join(output_dir, f"{project_name}_dfg.json")
    project_info_dfg = analyze_project(args.project_path)
    save_json(project_info_dfg, output_file_dfg)
    print(f"data flow graph analysis completed, results saved to {output_file_dfg}")
    
    # dependency analysis
    output_file_dep = os.path.join(output_dir, f"{project_name}_dependency.json")
    project_info_dep = analyze_java_project(args.project_path)
    save_json(project_info_dep, output_file_dep)
    print(f"dependency analysis completed, results saved to {output_file_dep}")
    
    # indirect dependency analysis
    output_file_idc = os.path.join(output_dir, f"{project_name}_IDC.json")
    analyzer = EnhancedJavaDependencyAnalyzer(args.project_path)
    analyzer.analyze()
    analyzer.save_to_json(output_file_idc)
    print(f"indirect dependency analysis completed, results saved to {output_file_idc}")

    # merge all analysis results
    combined_results = {
        "data_flow_graph": project_info_dfg,
        "dependencies": project_info_dep,
        "indirect_dependencies": {k: list(v) for k, v in analyzer.dependencies.items()}
    }
    output_file_combined = os.path.join(output_dir, f"{project_name}_combined_analysis.json")
    save_json(combined_results, output_file_combined)
    print(f"all analysis results merged and saved to {output_file_combined}")

if __name__ == "__main__":
    main()