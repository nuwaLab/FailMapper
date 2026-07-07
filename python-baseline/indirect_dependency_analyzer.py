import os
import re
import json
import ast
from collections import defaultdict

class EnhancedJavaDependencyAnalyzer:
    def __init__(self, project_path):
        self.project_path = project_path
        self.dependencies = defaultdict(set)
        self.indirect_dependencies = defaultdict(set)
        self.inheritance_map = defaultdict(set)

    def analyze(self):
        for root, _, files in os.walk(self.project_path):
            # print(root.split(os.path.sep))
            if 'test' in root.split(os.path.sep):
                continue
            for file in files:
                if file.endswith('.java') and file != 'module-info.java':  # Skip module-info.java files
                    self._analyze_file(os.path.join(root, file))
        
        self._find_indirect_dependencies()

    def _analyze_file(self, file_path):
        with open(file_path, 'r') as f:
            content = f.read()
        
        # 分析包名
        package_name = self._get_package_name(content)
        
        # 分析类名（包括内部类）
        classes = self._get_classes(content)
        
        for class_name in classes:
            full_class_name = f"{package_name}.{class_name}"
            
            # 分析导入
            imports = self._get_imports(content)
            self.dependencies[full_class_name].update(imports)
            
            # 分析继承和接口实现
            inheritance = self._get_inheritance(content, class_name)
            self.inheritance_map[full_class_name].update(inheritance)
            self.dependencies[full_class_name].update(inheritance)
            
            # 分析方法级依赖
            method_dependencies = self._get_method_dependencies(content, class_name)
            self.dependencies[full_class_name].update(method_dependencies)

    def _get_package_name(self, content):
        match = re.search(r'package\s+([\w.]+);', content)
        return match.group(1) if match else ""

    def _get_classes(self, content):
        return re.findall(r'class\s+(\w+)', content)

    def _get_imports(self, content):
        imports = re.findall(r'import\s+([\w.]+)(?:\.\*)?;', content)
        static_imports = re.findall(r'import\s+static\s+([\w.]+)(?:\.\*)?;', content)
        return imports + static_imports

    def _get_inheritance(self, content, class_name):
        pattern = rf'class\s+{class_name}\s+extends\s+([\w.]+)(?:\s+implements\s+([\w.,\s]+))?'
        match = re.search(pattern, content)
        if match:
            inheritance = [match.group(1)]
            if match.group(2):
                inheritance.extend(re.split(r',\s*', match.group(2)))
            return inheritance
        return []

    def _get_method_dependencies(self, content, class_name):
        # 这里我们使用一个简单的方法来检测方法内的类使用
        # 实际上，这需要更复杂的语法分析
        class_content = re.search(rf'class\s+{class_name}.*?{{(.*?)}}', content, re.DOTALL)
        if class_content:
            return set(re.findall(r'new\s+([\w.]+)', class_content.group(1)))
        return set()

    def _find_indirect_dependencies(self):
        for class_name, direct_deps in self.dependencies.items():
            visited = set()
            self._dfs(class_name, direct_deps, visited)

    def _dfs(self, class_name, deps, visited):
        for dep in deps:
            if dep not in visited and dep in self.dependencies:
                visited.add(dep)
                self.indirect_dependencies[class_name].add(dep)
                self._dfs(class_name, self.dependencies[dep], visited)

    def save_to_json(self, output_file):
        result = {
            "direct_dependencies": {k: list(v) for k, v in self.dependencies.items()},
            "indirect_dependencies": {k: list(v) for k, v in self.indirect_dependencies.items()},
            "inheritance_map": {k: list(v) for k, v in self.inheritance_map.items()}
        }
        with open(output_file, 'w') as f:
            json.dump(result, f, indent=2)

# Usage

# project_name = "spring-boot-mongodb"
# # project_name = "Tutorial_Stack"
# project_name = "Chat2DB"
# project_path = "/home/ricky/Desktop/unit_test/samples/" + project_name
# output_file = "/home/ricky/Desktop/unit_test/results/static_analysis/" + project_name + "_IDC.json"
# analyzer = EnhancedJavaDependencyAnalyzer(project_path)
# analyzer.analyze()
# analyzer.save_to_json(output_file)