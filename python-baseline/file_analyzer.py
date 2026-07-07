import javalang
import re
import os
from typing import Dict, Any, List
from class_analyzer import extract_class_info
from method_analyzer import extract_interface_info, extract_enum_info
from data_flow_analyzer import extract_data_flow_graph

def preprocess_java_content(content: str) -> str:
    """预处理Java代码，移除一些javalang无法处理的语法"""
    # 移除复杂的格式化字符串（Java 8+）
    content = re.sub(r'String\.format\([^)]*String\.format[^)]*\)', 'String.format(...)', content)
    
    # 简化复杂的泛型表达式
    content = re.sub(r'<[^<>]*<[^<>]*>[^<>]*>', '<...>', content)
    
    # 移除复杂的lambda表达式
    content = re.sub(r'->\s*\{[^}]*\}', '-> {...}', content)
    
    # 简化方法引用
    content = re.sub(r'::\w+', '::method', content)
    
    return content

def extract_basic_info_with_regex(content: str, file_path: str) -> Dict[str, Any]:
    """使用正则表达式提取基本信息作为fallback"""
    result = {
        "classes": [],
        "interfaces": [],
        "enums": [],
        "parsing_method": "regex_fallback",
        "file_path": file_path
    }
    
    # 提取类信息
    class_pattern = r'(?:public\s+)?(?:abstract\s+)?class\s+(\w+)(?:\s+extends\s+\w+)?(?:\s+implements\s+[\w,\s<>]+)?\s*\{'
    classes = re.findall(class_pattern, content)
    for class_name in classes:
        result["classes"].append({
            "name": class_name,
            "type": "class",
            "methods": extract_methods_with_regex(content, class_name),
            "fields": extract_fields_with_regex(content, class_name),
            "parsing_method": "regex"
        })
    
    # 提取接口信息
    interface_pattern = r'(?:public\s+)?interface\s+(\w+)(?:\s+extends\s+[\w,\s<>]+)?\s*\{'
    interfaces = re.findall(interface_pattern, content)
    for interface_name in interfaces:
        result["interfaces"].append({
            "name": interface_name,
            "type": "interface",
            "methods": extract_methods_with_regex(content, interface_name),
            "parsing_method": "regex"
        })
    
    # 提取枚举信息
    enum_pattern = r'(?:public\s+)?enum\s+(\w+)\s*\{'
    enums = re.findall(enum_pattern, content)
    for enum_name in enums:
        result["enums"].append({
            "name": enum_name,
            "type": "enum",
            "parsing_method": "regex"
        })
    
    return result

def extract_methods_with_regex(content: str, class_name: str) -> List[Dict[str, Any]]:
    """使用正则表达式提取方法信息"""
    methods = []
    
    # 简化的方法匹配模式
    method_pattern = r'(?:public|private|protected)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(\w+(?:<[^>]*>)?)\s+(\w+)\s*\([^)]*\)\s*(?:throws\s+[\w,\s]+)?\s*\{'
    
    for match in re.finditer(method_pattern, content):
        return_type = match.group(1)
        method_name = match.group(2)
        
        # 跳过构造函数
        if method_name != class_name:
            methods.append({
                "name": method_name,
                "return_type": return_type,
                "parameters": [],  # 简化处理
                "modifiers": [],
                "parsing_method": "regex"
            })
    
    return methods

def extract_fields_with_regex(content: str, class_name: str) -> List[Dict[str, Any]]:
    """使用正则表达式提取字段信息"""
    fields = []
    
    # 简化的字段匹配模式
    field_pattern = r'(?:public|private|protected)?\s*(?:static\s+)?(?:final\s+)?(\w+(?:<[^>]*>)?)\s+(\w+)\s*(?:=\s*[^;]+)?;'
    
    for match in re.finditer(field_pattern, content):
        field_type = match.group(1)
        field_name = match.group(2)
        
        fields.append({
            "name": field_name,
            "type": field_type,
            "modifiers": [],
            "parsing_method": "regex"
        })
    
    return fields

def analyze_java_file(file_path: str) -> Dict[str, Any]:
    """分析Java文件，支持多种解析策略"""
    try:
        with open(file_path, 'r', encoding='utf-8') as file:
            content = file.read()
    except Exception as e:
        return {
            "error": f"Failed to read file: {str(e)}",
            "classes": [],
            "interfaces": [],
            "enums": []
        }
    
    # 策略1: 直接使用javalang解析
    try:
        tree = javalang.parse.parse(content)
        return parse_with_javalang(tree, content)
    except Exception as e:
        print(f"Direct parsing failed for {file_path}: {str(e)}")
        
        # 策略2: 预处理后使用javalang解析
        try:
            preprocessed_content = preprocess_java_content(content)
            tree = javalang.parse.parse(preprocessed_content)
            result = parse_with_javalang(tree, preprocessed_content)
            result["parsing_method"] = "preprocessed_javalang"
            return result
        except Exception as e2:
            print(f"Preprocessed parsing failed for {file_path}: {str(e2)}")
            
            # 策略3: 使用正则表达式fallback
            try:
                result = extract_basic_info_with_regex(content, file_path)
                result["parse_error"] = str(e)
                result["fallback_reason"] = "javalang_parsing_failed"
                return result
            except Exception as e3:
                # 策略4: 返回最小信息
                return {
                    "error": f"All parsing strategies failed: javalang={str(e)}, preprocessed={str(e2)}, regex={str(e3)}",
                    "classes": [],
                    "interfaces": [],
                    "enums": [],
                    "parsing_method": "failed",
                    "file_path": file_path
                }

def parse_with_javalang(tree, content: str) -> Dict[str, Any]:
    """使用javalang AST解析"""
    classes = []
    interfaces = []
    enums = []
    
    for path, node in tree.filter(javalang.tree.TypeDeclaration):
        try:
            if isinstance(node, javalang.tree.ClassDeclaration):
                class_info = extract_class_info(node)
                try:
                    class_info['data_flow_graph'] = extract_data_flow_graph(node)
                except Exception as e:
                    class_info['data_flow_graph'] = {"error": str(e)}
                classes.append(class_info)
            elif isinstance(node, javalang.tree.InterfaceDeclaration):
                interfaces.append(extract_interface_info(node))
            elif isinstance(node, javalang.tree.EnumDeclaration):
                enums.append(extract_enum_info(node))
        except Exception as e:
            print(f"Error processing node {type(node)}: {str(e)}")
            # 继续处理其他节点
            continue
    
    return {
        "classes": classes,
        "interfaces": interfaces,
        "enums": enums,
        "parsing_method": "javalang"
    }