import os
import xml.etree.ElementTree as ET
import json
import re
import javalang

def analyze_java_project(project_path):
    project_info = {
        "name": os.path.basename(project_path),
        "structure": [],
        "dependencies": [],
        "configurations": [],
        "test_framework": None,
        "build_tool": None,
        "main_classes": [],
        "test_classes": [],
        "package_structure": set(),
        "frameworks": {},
        "testable_units": {},
        "existing_tests": {}
    }

    # Analyze project structure
    for root, dirs, files in os.walk(project_path):
        for file in files:
            file_path = os.path.join(root, file)
            project_info["structure"].append(file_path)
            if file.endswith(".java") and file != "module-info.java":  # Skip module-info.java files
                analyze_java_file(file_path, project_info)

    # Detect build tool and analyze dependencies
    if os.path.exists(os.path.join(project_path, "pom.xml")):
        project_info["build_tool"] = "Maven"
        analyze_maven_project(project_path, project_info)
    elif os.path.exists(os.path.join(project_path, "build.gradle")):
        project_info["build_tool"] = "Gradle"
        analyze_gradle_project(project_path, project_info)
    else:
        print("Warning: Neither pom.xml nor build.gradle found. Unable to determine build tool.")

    # Analyze configuration files
    analyze_config_files(project_path, project_info)

    # Post-processing
    project_info["package_structure"] = list(project_info["package_structure"])
    project_info["test_count"] = len(project_info["test_classes"])

    return project_info

def analyze_java_file(file_path, project_info):
    with open(file_path, 'r') as file:
        content = file.read()
        
    try:
        tree = javalang.parse.parse(content)
    except:
        print(f"Warning: Unable to parse {file_path}")
        analyze_test_file(file_path, project_info)
        return

    package = tree.package.name if tree.package else "default"
    project_info["package_structure"].add(package)

    for path, node in tree.filter(javalang.tree.TypeDeclaration):
        try:
            class_name = node.name
            full_class_name = f"{package}.{class_name}"

            if isinstance(node, javalang.tree.InterfaceDeclaration):
                # Handle interface
                project_info["testable_units"][full_class_name] = {
                    "file_path": file_path,
                    "package": package,
                    "class_name": class_name,
                    "type": "interface",
                    "methods": [{"name": method.name, "parameters": [param.type.name if param.type else "Unknown" for param in method.parameters], "return_type": method.return_type.name if method.return_type else "void"} for method in node.methods],
                    "imports": [imp.path for imp in tree.imports],
                    "annotations": [ann.name for ann in node.annotations] if node.annotations else [],
                    "extends": [extend.name for extend in node.extends] if node.extends else []
                }
            elif isinstance(node, javalang.tree.ClassDeclaration):
                if any(method.name == 'main' for method in node.methods):
                    project_info["main_classes"].append(os.path.basename(file_path))

                methods = []
                for method in node.methods:
                    param_types = [param.type.name if param.type else "Unknown" for param in method.parameters]
                    return_type = method.return_type.name if method.return_type else "void"
                    methods.append({
                        "name": method.name,
                        "parameters": param_types,
                        "return_type": return_type,
                        "visibility": list(method.modifiers) if method.modifiers else []
                    })

                fields = []
                for field in node.fields:
                    fields.append({
                        "name": field.declarators[0].name,
                        "type": field.type.name if field.type else "Unknown",
                        "visibility": list(field.modifiers) if field.modifiers else []
                    })

                project_info["testable_units"][full_class_name] = {
                    "file_path": file_path,
                    "package": package,
                    "class_name": class_name,
                    "type": "class",
                    "methods": methods,
                    "fields": fields,
                    "imports": [imp.path for imp in tree.imports],
                    "annotations": [ann.name for ann in node.annotations] if node.annotations else [],
                    "superclass": node.extends.name if node.extends else None,
                    "interfaces": [impl.name for impl in node.implements] if node.implements else []
                }

            if os.path.basename(file_path).endswith("Test.java") or any('org.junit' in imp.path for imp in tree.imports):
                project_info["test_classes"].append(os.path.basename(file_path))
                if not project_info["test_framework"]:
                    project_info["test_framework"] = "JUnit"
                analyze_test_file(file_path, project_info)
            elif any('org.testng' in imp.path for imp in tree.imports):
                project_info["test_classes"].append(os.path.basename(file_path))
                project_info["test_framework"] = "TestNG"
                analyze_test_file(file_path, project_info)

        except Exception as e:
            print(f"Warning: Error processing {file_path}: {str(e)}")

def analyze_test_file(file_path, project_info):
    with open(file_path, 'r') as file:
        content = file.read()
    
    # Find test methods using regex
    test_methods = re.findall(r'@Test\s+public\s+void\s+(\w+)', content)
    
    class_name = os.path.basename(file_path).replace(".java", "")
    project_info["existing_tests"][class_name] = test_methods
    
    # Identify test framework
    if "@RunWith" in content or "@ExtendWith" in content:
        project_info["test_framework"] = "JUnit"
    elif "extends TestCase" in content:
        project_info["test_framework"] = "JUnit 3"
    elif "@Test" in content:
        project_info["test_framework"] = "JUnit 4/5"

def analyze_maven_project(project_path, project_info):
    pom_path = os.path.join(project_path, "pom.xml")
    try:
        tree = ET.parse(pom_path)
        root = tree.getroot()

        ns = {"maven": "http://maven.apache.org/POM/4.0.0"}
        
        project_info["version"] = root.find("maven:version", ns).text if root.find("maven:version", ns) is not None else "Not specified"
        
        for dependency in root.findall(".//maven:dependency", ns):
            group_id = dependency.find("maven:groupId", ns)
            artifact_id = dependency.find("maven:artifactId", ns)
            version = dependency.find("maven:version", ns)
            
            if group_id is not None and artifact_id is not None:
                dep_str = f"{group_id.text}:{artifact_id.text}"
                if version is not None:
                    dep_str += f":{version.text}"
                else:
                    dep_str += ":version_not_specified"
                project_info["dependencies"].append(dep_str)

                # Identify frameworks
                if "spring-boot" in dep_str:
                    project_info["frameworks"]["Spring Boot"] = version.text if version is not None else "version_not_specified"

    except ET.ParseError:
        print(f"Warning: Failed to parse {pom_path}. It might be malformed.")
    except Exception as e:
        print(f"An error occurred while analyzing Maven project: {str(e)}")

def analyze_gradle_project(project_path, project_info):
    gradle_path = os.path.join(project_path, "build.gradle")
    try:
        with open(gradle_path, 'r') as file:
            content = file.read()
            dependencies = re.findall(r"(implementation|api|compile) ['\"](.+?)['\"]", content)
            project_info["dependencies"].extend([dep[1] for dep in dependencies])

            # Identify frameworks
            spring_boot_version = re.search(r"springBootVersion\s*=\s*['\"](.+?)['\"]", content)
            if spring_boot_version:
                project_info["frameworks"]["Spring Boot"] = spring_boot_version.group(1)

    except Exception as e:
        print(f"An error occurred while analyzing Gradle project: {str(e)}")

def analyze_config_files(project_path, project_info):
    config_files = [
        "application.properties",
        "application.yml",
        "application-dev.properties",
        "application-prod.properties"
    ]
    
    for config_file in config_files:
        file_path = os.path.join(project_path, "src", "main", "resources", config_file)
        if os.path.exists(file_path):
            try:
                with open(file_path, 'r') as file:
                    project_info["configurations"].append({config_file: file.read()})
            except Exception as e:
                print(f"An error occurred while reading {config_file}: {str(e)}")

def main():
    project_name = "spring-boot-mongodb"
    # project_name = "Tutorial_Stack"
    project_path = "/home/ricky/Desktop/unit_test/test/" + project_name
    output_file = "/home/ricky/Desktop/unit_test/results/static_analysis/" + project_name + "_dependency.json"
    project_info = analyze_java_project(project_path)
    
    # Post-processing: Ensure test framework is recognized
    if project_info["test_framework"] is None and project_info["test_classes"]:
        project_info["test_framework"] = "Unknown (JUnit assumed)"
    
    print(json.dumps(project_info, indent=2))
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(project_info, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()