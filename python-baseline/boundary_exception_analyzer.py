import os
import re

class JavaMethodAnalyzer:
    def __init__(self):
        self.boundary_conditions = []
        self.exception_handling = []

    def analyze_file(self, file_path):
        with open(file_path, 'r') as file:
            content = file.read()
        
        # 改进的方法提取模式，考虑泛型和多行声明
        method_pattern = r'(public|private|protected)?\s*(?:<.*?>)?\s*\w+\s+(\w+)\s*\([^)]*\)\s*(?:throws\s+[\w,\s]+)?\s*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}'
        methods = re.findall(method_pattern, content, re.DOTALL)

        results = []
        for method in methods:
            method_name = method[1]
            self.boundary_conditions = []
            self.exception_handling = []
            
            self.analyze_method(method[0])
            
            results.append({
                'method_name': method_name,
                'boundary_conditions': self.boundary_conditions,
                'exception_handling': self.exception_handling
            })
        
        return results

    def analyze_method(self, method_content):
        # 改进的if语句模式，考虑更复杂的条件
        if_pattern = r'if\s*\((.*?)\)\s*(?:\{|[^;{]+;)'
        conditions = re.findall(if_pattern, method_content, re.DOTALL)
        for condition in conditions:
            if self.is_boundary_condition(condition):
                self.boundary_conditions.append(condition.strip())

        # 改进的异常处理模式，包括throw语句和try-catch块
        throw_pattern = r'throw\s+new\s+(\w+)'
        try_pattern = r'try\s*\{.*?\}\s*catch\s*\((.*?)\)'
        
        throws = re.findall(throw_pattern, method_content)
        for throw in throws:
            self.exception_handling.append(f"Throws {throw}")

        exceptions = re.findall(try_pattern, method_content, re.DOTALL)
        for exception in exceptions:
            self.exception_handling.append(f"Catches {exception.strip()}")

    def is_boundary_condition(self, condition):
        # 扩展边界条件检查
        operators = ['<', '<=', '>', '>=', '==', '!=']
        comparisons = re.findall(r'(\w+)\s*([<>=!]+)\s*(\w+)', condition)
        return any(op in operators for _, op, _ in comparisons)

def analyze_boundary_and_exception(project_path):
    analyzer = JavaMethodAnalyzer()
    results = {}
    for root, dirs, files in os.walk(project_path):
        for file in files:
            if file.endswith('.java'):
                file_path = os.path.join(root, file)
                relative_path = os.path.relpath(file_path, project_path)
                results[relative_path] = analyzer.analyze_file(file_path)
    return results

# 测试代码
# if __name__ == "__main__":
#     test_content = """
#     public class Stack<T> {
#         private int capacity = 10;
#         private int pointer  = 0;
#         private T[] objects = (T[]) new Object[capacity];
        
#         public void push(T o) {
#             if(pointer >= capacity)
#                 throw new RuntimeException("Stack exceeded capacity!");
#             objects[pointer++] = o;
#         }

#         public T pop() {
#             if(pointer <= 0)
#                 throw new EmptyStackException();
#             return objects[--pointer];
#         }
        
#         public boolean isEmpty() {
#             return pointer <= 0;
#         } 
#     }
#     """
#     analyzer = JavaMethodAnalyzer()
#     results = analyzer.analyze_file("test.java")
#     print(json.dumps(results, indent=2))