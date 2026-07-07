#!/usr/bin/env python3
"""
Implementation Analyzer

This module extracts implementation features from code,
focusing on control flow, data operations, and algorithm patterns.
"""

import re
import logging
from typing import Dict, Any, List

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("implementation_analyzer")

class ImplementationAnalyzer:
    """
    Analyzes code implementation features to understand actual behavior
    """
    
    def __init__(self):
        """Initialize the implementation analyzer"""
        # Algorithm patterns
        self.algorithm_patterns = {
            "sorting": [
                (r'Arrays\.sort', 'java_builtin_sort'),
                (r'Collections\.sort', 'java_builtin_sort'),
                (r'for\s*\(.*?;.*?;.*?\)\s*\{[^{}]*for\s*\(.*?;.*?;.*?\)\s*\{[^{}]*(?:if\s*\([^{}]*(?:<|>)[^{}]*\))[^{}]*(?:=|swap)[^{}]*\}', 'nested_loop_comparison_swap')
            ],
            "searching": [
                (r'Arrays\.(?:binary)?search', 'java_builtin_search'),
                (r'Collections\.(?:binary)?search', 'java_builtin_search'),
                (r'while\s*\([^{}]*(?:start|begin|low)[^{}]*(?:<|<=)[^{}]*(?:end|high)[^{}]*\)[^{}]*(?:mid|middle)[^{}]*=', 'binary_search')
            ],
            "data_processing": [
                (r'.*?\.stream\(\).*?\.(?:map|filter|reduce)', 'java_stream_processing'),
                (r'for\s*\(\s*(?:final\s+)?(?:\w+)(?:<[^>]*>)?\s+\w+\s*:\s*\w+\s*\)', 'for_each_loop')
            ]
        }
        
        # Data operation patterns
        self.data_operation_patterns = {
            "collection_modification": [
                (r'(\w+)\.add\(', 'collection_add'),
                (r'(\w+)\.remove\(', 'collection_remove'),
                (r'(\w+)\.addAll\(', 'collection_add_all'),
                (r'(\w+)\.clear\(', 'collection_clear')
            ],
            "string_manipulation": [
                (r'(\w+)\.concat\(', 'string_concat'),
                (r'(\w+)\.substring\(', 'string_substring'),
                (r'(\w+)\.replace\(', 'string_replace'),
                (r'(\w+)\.split\(', 'string_split'),
                (r'(\w+)\.toLowerCase\(', 'string_case_conversion'),
                (r'(\w+)\.toUpperCase\(', 'string_case_conversion')
            ],
            "object_creation": [
                (r'new\s+(\w+)\(', 'object_instantiation'),
                (r'(\w+)\.build\(', 'builder_pattern')
            ]
        }
    
    def extract_implementation_features(self, source_code: str, class_name: str, method_name: str) -> Dict[str, Any]:
        """
        Extract all implementation features from the code
        
        Parameters:
        source_code (str): Source code
        class_name (str): Class name
        method_name (str): Method name
        
        Returns:
        dict: Implementation features
        """
        try:
            # If method_name matches class_name, analyze the entire class
            if method_name == class_name:
                logger.info(f"Analyzing entire class: {class_name}")
                class_code = self._extract_class_code(source_code, class_name)
                if not class_code:
                    logger.warning(f"Could not extract class code for {class_name}")
                    return {}
                
                # Analyze all methods in the class
                methods = self._extract_all_methods(class_code)
                features = {
                    "class_name": class_name,
                    "methods": {}
                }
                
                for method_name, method_code in methods.items():
                    method_features = self._analyze_method_implementation(method_code)
                    features["methods"][method_name] = method_features
                
                return features
            
            # Otherwise, extract the specific method
            method_code = self._extract_method_code(source_code, method_name)
            
            if not method_code:
                # Try with more flexible method extraction as fallback
                method_code = self._extract_method_code_flexible(source_code, method_name)
                if not method_code:
                    logger.warning(f"Could not extract method code for {method_name}")
                    return {}
            
            return self._analyze_method_implementation(method_code)
            
        except Exception as e:
            logger.error(f"Error extracting implementation features: {str(e)}")
            return {}
    
    def _analyze_method_implementation(self, method_code: str) -> Dict[str, Any]:
        """
        Analyze a method's implementation features
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        dict: Method implementation features
        """
        # Analyze control flow
        control_flow = self._analyze_control_flow(method_code)
        
        # Analyze data operations
        data_operations = self._analyze_data_operations(method_code)
        
        # Extract boundary conditions
        boundary_conditions = self._analyze_boundary_conditions(method_code)
        
        # Analyze error handling
        error_handling = self._analyze_error_handling(method_code)
        
        # Identify algorithm patterns
        algorithmic_patterns = self._identify_algorithm_patterns(method_code)
        
        return {
            "control_flow": control_flow,
            "data_operations": data_operations,
            "boundary_conditions": boundary_conditions,
            "error_handling": error_handling,
            "algorithmic_patterns": algorithmic_patterns
        }
    
    def _extract_class_code(self, source_code: str, class_name: str) -> str:
        """
        Extract entire class code from source
        
        Parameters:
        source_code (str): Source code
        class_name (str): Class name
        
        Returns:
        str: Class code
        """
        # Look for the class declaration
        class_pattern = r'(?:public|private|protected)?\s+(?:abstract\s+)?(?:static\s+)?(?:final\s+)?class\s+' + \
                       re.escape(class_name) + \
                       r'(?:<[^>]+>)?(?:\s+extends\s+\w+(?:<[^>]+>)?)?(?:\s+implements\s+[^{]+)?'
        
        class_match = re.search(class_pattern, source_code, re.DOTALL)
        if not class_match:
            # Try alternative pattern with just the class name
            alt_pattern = r'\bclass\s+' + re.escape(class_name) + r'\b'
            class_match = re.search(alt_pattern, source_code, re.DOTALL)
            if not class_match:
                return ""
        
        # Find the position of the class start
        class_start = class_match.start()
        
        # Find the opening brace after the class declaration
        opening_brace_pos = source_code.find('{', class_start)
        if opening_brace_pos == -1:
            return ""
        
        # Count braces to find the matching closing brace
        brace_count = 1
        pos = opening_brace_pos + 1
        
        while brace_count > 0 and pos < len(source_code):
            if source_code[pos] == '{':
                brace_count += 1
            elif source_code[pos] == '}':
                brace_count -= 1
            pos += 1
        
        if brace_count != 0:
            return ""  # Unbalanced braces
        
        # Extract the full class including declaration and body
        return source_code[class_start:pos]
    
    def _extract_all_methods(self, class_code: str) -> Dict[str, str]:
        """
        Extract all methods from a class
        
        Parameters:
        class_code (str): Class code
        
        Returns:
        dict: Dictionary mapping method names to their code
        """
        methods = {}
        
        # Pattern to match method declarations
        method_pattern = r'(?:public|private|protected)?\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:<[^>]+>\s+)?(?:\w+(?:<[^>]+>)?)\s+(\w+)\s*\([^)]*\)(?:\s+throws\s+[^{]+)?\s*\{((?:[^{}]|(?:\{(?:[^{}]|(?:\{[^{}]*\}[^{}]*))*\}))*)\}'
        
        method_matches = re.finditer(method_pattern, class_code, re.DOTALL)
        
        for match in method_matches:
            method_name = match.group(1)
            method_body = match.group(0)  # Full method including signature
            methods[method_name] = method_body
        
        return methods
    
    def _extract_method_code(self, source_code: str, method_name: str) -> str:
        """
        Extract method code from source
        
        Parameters:
        source_code (str): Source code
        method_name (str): Method name
        
        Returns:
        str: Method code
        """
        # Look for the method signature line first
        signature_pattern = r'(?:public|private|protected)?\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:<[^>]+>\s+)?(?:\w+(?:<[^>]+>)?)\s+' + \
                            re.escape(method_name) + \
                            r'\s*\([^)]*\)(?:\s+throws\s+[^{]+)?'
        
        signature_match = re.search(signature_pattern, source_code, re.DOTALL)
        if not signature_match:
            return ""
        
        # Find the position of the method start
        method_start = signature_match.start()
        
        # Find the opening brace after the method signature
        opening_brace_pos = source_code.find('{', method_start)
        if opening_brace_pos == -1:
            return ""
        
        # Count braces to find the matching closing brace
        brace_count = 1
        pos = opening_brace_pos + 1
        
        while brace_count > 0 and pos < len(source_code):
            if source_code[pos] == '{':
                brace_count += 1
            elif source_code[pos] == '}':
                brace_count -= 1
            pos += 1
        
        if brace_count != 0:
            return ""  # Unbalanced braces
        
        # Extract the full method including signature and body
        return source_code[method_start:pos]
    
    def _extract_method_code_flexible(self, source_code: str, method_name: str) -> str:
        """
        Extract method code with a more flexible approach
        
        Parameters:
        source_code (str): Source code
        method_name (str): Method name
        
        Returns:
        str: Method code
        """
        # Try to find the method name with its parameter list
        method_pattern = method_name + r'\s*\([^)]*\)'
        method_match = re.search(method_pattern, source_code)
        
        if not method_match:
            return ""
        
        # Find the method start by searching backwards
        method_start = method_match.start()
        # Look back a reasonable amount to find modifiers
        potential_start = max(0, method_start - 100)
        method_text = source_code[potential_start:method_start]
        
        # Look for method modifiers
        modifiers = ["public", "private", "protected", "static", "final", "synchronized", "abstract", "native"]
        for modifier in modifiers:
            pattern = r'\b' + modifier + r'\b'
            mod_match = re.search(pattern, method_text)
            if mod_match:
                method_start = potential_start + mod_match.start()
                break
        
        # Find the opening brace after the method name
        opening_brace_pos = source_code.find('{', method_match.end())
        if opening_brace_pos == -1:
            return ""
            
        # Count braces to find the matching closing brace
        brace_count = 1
        pos = opening_brace_pos + 1
        
        while brace_count > 0 and pos < len(source_code):
            if source_code[pos] == '{':
                brace_count += 1
            elif source_code[pos] == '}':
                brace_count -= 1
            pos += 1
        
        if brace_count != 0:
            return ""  # Unbalanced braces
            
        # Extract the full method
        return source_code[method_start:pos]
    
    def _analyze_control_flow(self, method_code: str) -> Dict[str, Any]:
        """
        Analyze control flow structures
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        dict: Control flow analysis
        """
        # Count control structures
        if_count = len(re.findall(r'\bif\s*\(', method_code))
        else_count = len(re.findall(r'\belse\b', method_code))
        for_count = len(re.findall(r'\bfor\s*\(', method_code))
        while_count = len(re.findall(r'\bwhile\s*\(', method_code))
        do_while_count = len(re.findall(r'\bdo\b', method_code))
        switch_count = len(re.findall(r'\bswitch\s*\(', method_code))
        return_count = len(re.findall(r'\breturn\b', method_code))
        
        # Count nested control structures
        nested_if_count = len(re.findall(r'\bif\s*\([^{]*\)\s*\{[^{}]*\bif\s*\(', method_code))
        nested_loop_count = len(re.findall(r'\b(?:for|while)\s*\([^{]*\)\s*\{[^{}]*\b(?:for|while)\s*\(', method_code))
        
        # Identify early returns
        early_returns = self._has_early_returns(method_code)
        
        # Calculate cyclomatic complexity (simplified)
        complexity = 1 + if_count + for_count + while_count + do_while_count + switch_count
        
        # Get conditional expressions
        conditions = self._extract_conditions(method_code)
        
        return {
            "if_count": if_count,
            "else_count": else_count,
            "for_count": for_count,
            "while_count": while_count,
            "do_while_count": do_while_count,
            "switch_count": switch_count,
            "return_count": return_count,
            "nested_if_count": nested_if_count,
            "nested_loop_count": nested_loop_count,
            "has_early_returns": early_returns,
            "cyclomatic_complexity": complexity,
            "conditions": conditions
        }
    
    def _analyze_data_operations(self, method_code: str) -> Dict[str, Any]:
        """
        Analyze data operations
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        dict: Data operations analysis
        """
        # Extract variables and assignments
        variables = self._extract_variables(method_code)
        assignments = self._extract_assignments(method_code)
        
        # Detect data operation patterns
        data_ops = {}
        for category, patterns in self.data_operation_patterns.items():
            ops = []
            for pattern, op_type in patterns:
                matches = re.finditer(pattern, method_code)
                for match in matches:
                    try:
                        ops.append({
                            "type": op_type,
                            "variable": match.group(1),
                            "operation": match.group(0)
                        })
                    except IndexError:
                        # Handle case where the pattern doesn't have a capture group
                        ops.append({
                            "type": op_type,
                            "operation": match.group(0)
                        })
            if ops:
                data_ops[category] = ops
        
        # Detect collection operations
        collection_ops = self._detect_collection_operations(method_code)
        if collection_ops:
            data_ops["collection_operations"] = collection_ops
        
        # Detect string operations
        string_ops = self._detect_string_operations(method_code)
        if string_ops:
            data_ops["string_operations"] = string_ops
        
        # Detect math operations
        math_ops = self._detect_math_operations(method_code)
        if math_ops:
            data_ops["math_operations"] = math_ops
        
        return {
            "variables": variables,
            "assignments": assignments,
            "operations": data_ops
        }
    
    def _analyze_boundary_conditions(self, method_code: str) -> List[Dict[str, Any]]:
        """
        Analyze boundary conditions
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        list: Boundary conditions
        """
        boundary_conditions = []
        
        # Look for boundary checks
        if_pattern = r'if\s*\(\s*([^{};()]+?)\s*([<>=!]+)\s*([^{};()]+?)\s*\)'
        conditions = re.finditer(if_pattern, method_code)
        
        for match in conditions:
            left = match.group(1).strip()
            operator = match.group(2)
            right = match.group(3).strip()
            
            # Check if this looks like a boundary check
            is_boundary = False
            
            # Common boundary values
            if (right in ["0", "1", "-1", "null", "true", "false"] or 
                left in ["0", "1", "-1", "null", "true", "false"] or
                ".length" in left or ".length" in right or
                ".size()" in left or ".size()" in right):
                is_boundary = True
            
            # Check for boundary-like expressions
            if (re.search(r'\.length\s*-\s*1', left) or re.search(r'\.length\s*-\s*1', right) or
                re.search(r'\.size\(\)\s*-\s*1', left) or re.search(r'\.size\(\)\s*-\s*1', right)):
                is_boundary = True
            
            if is_boundary:
                boundary_conditions.append({
                    "type": "boundary_check",
                    "left": left,
                    "operator": operator,
                    "right": right,
                    "is_zero_check": right == "0" or left == "0",
                    "is_null_check": right == "null" or left == "null",
                    "is_length_check": ".length" in left or ".length" in right or ".size()" in left or ".size()" in right
                })
        
        # Look for array access with boundary checks
        array_pattern = r'(\w+)\[([^]]+)]'
        array_accesses = re.finditer(array_pattern, method_code)
        
        for match in array_accesses:
            array_name = match.group(1)
            index_expr = match.group(2)
            
            # Check if there's a boundary check for this array access
            has_check = False
            for condition in boundary_conditions:
                if (condition["left"] == index_expr and condition["operator"] in ["<", "<="] and 
                    f"{array_name}.length" in condition["right"]):
                    has_check = True
                    break
            
            boundary_conditions.append({
                "type": "array_access",
                "array": array_name,
                "index": index_expr,
                "has_boundary_check": has_check
            })
        
        return boundary_conditions
    
    def _analyze_error_handling(self, method_code: str) -> List[Dict[str, Any]]:
        """
        Analyze error handling
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        list: Error handling mechanisms
        """
        error_handling = []
        
        # Extract method signature to check for throws declarations
        method_signature = self._extract_method_signature(method_code)
        if method_signature:
            throws_types = self._extract_throws_declarations(method_signature)
            for exception_type in throws_types:
                error_handling.append({
                    "type": "throws_declaration",
                    "exception_type": exception_type
                })
        
        # Check for try-catch blocks
        try_pattern = r'try\s*\{((?:[^{}]|(?:\{[^{}]*\}))*)\}\s*catch\s*\(\s*(\w+(?:\.\w+)*)\s+\w+\s*\)\s*\{((?:[^{}]|(?:\{[^{}]*\}))*)\}'
        
        try_blocks = re.finditer(try_pattern, method_code, re.DOTALL)
        
        for match in try_blocks:
            try_block = match.group(1)
            exception_type = match.group(2)
            catch_block = match.group(3)
            
            error_handling.append({
                "type": "try_catch",
                "exception_type": exception_type,
                "is_empty_catch": not bool(catch_block.strip()),
                "handles_exception": "throw" in catch_block or "log" in catch_block or len(catch_block.strip()) > 0
            })
        
        # Check for throw statements
        throw_pattern = r'throw\s+(?:new\s+)?(\w+(?:\.\w+)*)(?:\([^)]*\))?'
        throws = re.finditer(throw_pattern, method_code)
        
        for match in throws:
            exception_type = match.group(1)
            error_handling.append({
                "type": "throw",
                "exception_type": exception_type
            })
        
        # Check for null checks before operations
        null_check_pattern = r'if\s*\(\s*(\w+)\s*(?:==|!=)\s*null\s*\)'
        null_checks = re.finditer(null_check_pattern, method_code)
        
        for match in null_checks:
            var_name = match.group(1)
            error_handling.append({
                "type": "null_check",
                "variable": var_name
            })
            
        # Check for EmptyStackException, RuntimeException, and other specific exceptions
        specific_exceptions = [
            "EmptyStackException", "RuntimeException", "IllegalArgumentException", 
            "NullPointerException", "IndexOutOfBoundsException"
        ]
        
        for exception in specific_exceptions:
            if exception in method_code:
                error_handling.append({
                    "type": "specific_exception",
                    "exception_type": exception
                })
        
        return error_handling
    
    def _extract_method_signature(self, method_code: str) -> str:
        """
        Extract method signature from method code
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        str: Method signature
        """
        signature_pattern = r'^.*?\)\s*(?:throws\s+[^{]+)?'
        signature_match = re.search(signature_pattern, method_code, re.DOTALL)
        
        if signature_match:
            return signature_match.group(0)
        return ""
    
    def _extract_throws_declarations(self, method_signature: str) -> List[str]:
        """
        Extract exception types from throws declarations
        
        Parameters:
        method_signature (str): Method signature
        
        Returns:
        list: Exception types
        """
        throws_pattern = r'throws\s+([\w\s,\.]+)'
        throws_match = re.search(throws_pattern, method_signature)
        
        if throws_match:
            exceptions_str = throws_match.group(1)
            # Split by comma and clean up whitespace
            exceptions = [ex.strip() for ex in exceptions_str.split(',')]
            return exceptions
        
        return []
    
    def _identify_algorithm_patterns(self, method_code: str) -> Dict[str, Any]:
        """
        Identify algorithm patterns
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        dict: Algorithm patterns
        """
        detected_patterns = {}
        
        for category, patterns in self.algorithm_patterns.items():
            for pattern, pattern_type in patterns:
                if re.search(pattern, method_code, re.DOTALL):
                    if category not in detected_patterns:
                        detected_patterns[category] = []
                    detected_patterns[category].append(pattern_type)
        
        # Special case for sorting directions
        if "sorting" in detected_patterns or "Arrays.sort" in method_code or "Collections.sort" in method_code:
            sorting_direction = self._detect_sorting_direction(method_code)
            if sorting_direction:
                detected_patterns["sorting_direction"] = sorting_direction
        
        # Special case for search patterns
        if "searching" in detected_patterns:
            search_target = self._detect_search_target(method_code)
            if search_target:
                detected_patterns["search_target"] = search_target
        
        return detected_patterns
    
    def _extract_variables(self, method_code: str) -> List[Dict[str, Any]]:
        """Extract variable declarations"""
        variables = []
        
        # Match variable declarations
        var_pattern = r'(?:final\s+)?(\w+)(?:<[^>]+>)?\s+(\w+)\s*(?:=\s*([^;]+))?;'
        var_matches = re.finditer(var_pattern, method_code)
        
        for match in var_matches:
            var_type = match.group(1)
            var_name = match.group(2)
            initializer = match.group(3) if match.group(3) else None
            
            variables.append({
                "name": var_name,
                "type": var_type,
                "has_initializer": bool(initializer),
                "initializer": initializer
            })
        
        return variables
    
    def _extract_assignments(self, method_code: str) -> List[Dict[str, Any]]:
        """Extract variable assignments"""
        assignments = []
        
        # Match assignments
        assign_pattern = r'(\w+)\s*(?:=|\+=|-=|\*=|/=|%=|\^=|&=|\|=|<<=|>>=|>>>=)\s*([^;]+);'
        assign_matches = re.finditer(assign_pattern, method_code)
        
        for match in assign_matches:
            var_name = match.group(1)
            value = match.group(2)
            
            assignments.append({
                "variable": var_name,
                "value": value
            })
        
        return assignments
    
    def _extract_conditions(self, method_code: str) -> List[Dict[str, Any]]:
        """Extract conditional expressions"""
        conditions = []
        
        # Match if conditions
        if_pattern = r'if\s*\(\s*([^{};()]+?)\s*\)'
        if_matches = re.finditer(if_pattern, method_code)
        
        for match in if_matches:
            condition = match.group(1).strip()
            
            # Check for comparison operators
            has_comparison = any(op in condition for op in ['==', '!=', '<', '>', '<=', '>='])
            
            # Check for logical operators
            has_logical_op = '&&' in condition or '||' in condition
            
            # Check for negation
            has_negation = condition.startswith('!') or ' !' in condition
            
            conditions.append({
                "type": "if_condition",
                "condition": condition,
                "has_comparison": has_comparison,
                "has_logical_op": has_logical_op,
                "has_negation": has_negation
            })
        
        # Match loop conditions
        loop_pattern = r'(?:while|for)\s*\(\s*([^{};()]+?)\s*\)'
        loop_matches = re.finditer(loop_pattern, method_code)
        
        for match in loop_matches:
            condition = match.group(1).strip()
            
            conditions.append({
                "type": "loop_condition",
                "condition": condition
            })
        
        return conditions
    
    def _has_early_returns(self, method_code: str) -> bool:
        """Check if method has early returns"""
        # Convert to lines for easier analysis
        lines = method_code.split('\n')
        
        if_return_pattern = r'\s*if\s*\([^{]*\)\s*\{\s*return\b'
        for line in lines:
            if re.search(if_return_pattern, line):
                return True
        
        return False
    
    def _detect_collection_operations(self, method_code: str) -> List[Dict[str, str]]:
        """Detect operations on collections"""
        operations = []
        
        # Common collection operations
        collection_ops = [
            (r'(\w+)\.add\(([^)]+)\)', 'add'),
            (r'(\w+)\.remove\(([^)]+)\)', 'remove'),
            (r'(\w+)\.get\(([^)]+)\)', 'get'),
            (r'(\w+)\.set\(([^)]+),\s*([^)]+)\)', 'set'),
            (r'(\w+)\.contains\(([^)]+)\)', 'contains'),
            (r'(\w+)\.isEmpty\(\)', 'isEmpty'),
            (r'(\w+)\.clear\(\)', 'clear'),
            (r'(\w+)\.size\(\)', 'size')
        ]
        
        for pattern, op_type in collection_ops:
            matches = re.finditer(pattern, method_code)
            for match in matches:
                collection_name = match.group(1)
                operations.append({
                    "type": op_type,
                    "collection": collection_name
                })
        
        return operations
    
    def _detect_string_operations(self, method_code: str) -> List[Dict[str, str]]:
        """Detect operations on strings"""
        operations = []
        
        # Common string operations
        string_ops = [
            (r'(\w+)\.charAt\(([^)]+)\)', 'charAt'),
            (r'(\w+)\.substring\(([^)]+)\)', 'substring'),
            (r'(\w+)\.length\(\)', 'length'),
            (r'(\w+)\.equals\(([^)]+)\)', 'equals'),
            (r'(\w+)\.startsWith\(([^)]+)\)', 'startsWith'),
            (r'(\w+)\.endsWith\(([^)]+)\)', 'endsWith'),
            (r'(\w+)\.replace\(([^)]+),\s*([^)]+)\)', 'replace'),
            (r'(\w+)\.split\(([^)]+)\)', 'split')
        ]
        
        for pattern, op_type in string_ops:
            matches = re.finditer(pattern, method_code)
            for match in matches:
                string_name = match.group(1)
                operations.append({
                    "type": op_type,
                    "string": string_name
                })
        
        return operations
    
    def _detect_math_operations(self, method_code: str) -> List[Dict[str, str]]:
        """Detect math operations"""
        operations = []
        
        # Basic math operators
        math_op_pattern = r'(\w+)\s*([+\-*/%])\s*(\w+)'
        math_matches = re.finditer(math_op_pattern, method_code)
        
        for match in math_matches:
            left = match.group(1)
            operator = match.group(2)
            right = match.group(3)
            
            operations.append({
                "type": "basic_math",
                "operator": operator,
                "left": left,
                "right": right
            })
        
        # Math library calls
        math_lib_pattern = r'Math\.(\w+)\('
        math_lib_matches = re.finditer(math_lib_pattern, method_code)
        
        for match in math_lib_matches:
            function = match.group(1)
            operations.append({
                "type": "math_library",
                "function": function
            })
        
        return operations
    
    def _detect_sorting_direction(self, method_code: str) -> str:
        """Detect sorting direction (ascending/descending)"""
        # Check for comparison operators in sorting context
        if re.search(r'if\s*\([^<>]*<[^<>]*\)[^{}]*(?:swap|=)', method_code):
            return "ascending"
        elif re.search(r'if\s*\([^<>]*>[^<>]*\)[^{}]*(?:swap|=)', method_code):
            return "descending"
        
        # Check for built-in sort with comparator
        if re.search(r'Collections\.sort\(.*new\s+Comparator.*\{\s*(?:@Override)?\s*public\s+int\s+compare\s*\([^)]*\)\s*\{[^}]*<[^}]*\}\s*\}', method_code):
            return "ascending"
        elif re.search(r'Collections\.sort\(.*new\s+Comparator.*\{\s*(?:@Override)?\s*public\s+int\s+compare\s*\([^)]*\)\s*\{[^}]*>[^}]*\}\s*\}', method_code):
            return "descending"
        
        # Check for reverse order
        if "Collections.reverseOrder()" in method_code:
            return "descending"
        
        # Default to ascending for standard API calls without explicit direction
        if "Arrays.sort(" in method_code and "Collections.reverseOrder()" not in method_code:
            return "ascending"
        elif "Collections.sort(" in method_code and "Collections.reverseOrder()" not in method_code:
            return "ascending"
        
        return ""
    
    def _detect_search_target(self, method_code: str) -> str:
        """Try to determine what is being searched for"""
        # Check for common search patterns
        search_pattern = r'(?:find|search|get)(?:\w+)?\(\s*(\w+)[^)]*\)'
        search_match = re.search(search_pattern, method_code)
        
        if search_match:
            return search_match.group(1)
        
        return ""