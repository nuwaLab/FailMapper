#!/usr/bin/env python3
"""
Logic Bug Pattern Detector

This module defines and detects common logical bug patterns in Java source code,
which can be targeted by the test generation process. It includes pattern detectors
for various categories of logical bugs based on academic research and practical
experience with real bugs from defect repositories.
"""

import re
import os
import json
import logging
from collections import defaultdict

logger = logging.getLogger("logic_bug_patterns")

class FS_Detector:
    """
    Detects common logical bug patterns in Java source code.
    These patterns represent categories of logic bugs that are common
    in real-world code and can be specifically targeted by tests.
    """
    
    # 在 FS_Detector.__init__ 方法中更新检测器列表
    def __init__(self, source_code, class_name, package_name, f_model=None):
        """
        Initialize with source code to analyze
        
        Parameters:
        source_code (str): Java source code
        class_name (str): Class name
        package_name (str): Package name
        f_model (object, optional): Logic model for enhanced detection
        """
        self.source_code = source_code
        self.class_name = class_name
        self.package_name = package_name
        self.lines = source_code.split('\n')
        self.f_model = f_model
        
        # Pattern detection results
        self.patterns = []
        
        # Register all pattern detectors
        self.detectors = [
            self._detect_operator_precedence_bugs,
            self._detect_off_by_one_bugs,
            self._detect_boundary_condition_bugs,
            self._detect_null_handling_bugs,
            self._detect_string_comparison_bugs,
            self._detect_boolean_bugs,
            self._detect_resource_leaks,
            self._detect_state_corruption_bugs,
            self._detect_integer_overflow_bugs,
            self._detect_copy_paste_bugs,
            self._detect_floating_point_comparison,
            self._detect_exception_handling_bugs,
            self._detect_complex_loop_conditions,
            self._detect_resource_management_defects,
            self._detect_data_operation_bugs,
            self._detect_concurrency_issues,
            self._detect_error_propagation_issues,
            self._detect_improper_validation,
            self._detect_security_vulnerabilities,
            self._detect_string_index_bounds_bugs,  # 添加新的字符串索引边界检测器
            self._detect_array_index_bounds_bugs,
        ]

        #logic bug patterns
        # self.detectors = [
        #     self._detect_operator_precedence_bugs,
        #     self._detect_off_by_one_bugs,
        #     self._detect_boundary_condition_bugs,
        #     self._detect_string_comparison_bugs,
        #     self._detect_boolean_bugs,
        #     self._detect_integer_overflow_bugs,
        #     self._detect_copy_paste_bugs,
        #     self._detect_floating_point_comparison,
        #     self._detect_complex_loop_conditions,
        #     self._detect_data_operation_bugs,
        #     self._detect_string_index_bounds_bugs,
        #     self._detect_array_index_bounds_bugs,
        # ]



    
    def detect_patterns(self):
        """
        Detect all registered logic bug patterns in the source code
        
        Returns:
        list: Detected patterns
        """
        for detector in self.detectors:
            try:
                detector()
            except Exception as e:
                logger.error(f"Error in pattern detector {detector.__name__}: {str(e)}")
        
        # Sort patterns by risk level (high to low)
        self.patterns.sort(key=lambda p: {"high": 3, "medium": 2, "low": 1}.get(p["risk_level"], 0), reverse=True)
        
        return self.patterns
    
    def _detect_operator_precedence_bugs(self):
        """Detect potential operator precedence bugs"""
        # Look for complex expressions with mixed operators without parentheses
        mixed_operators_pattern = r'([^()]*?[&|<>=!^]+[^()]*?[&|<>=!^]+[^()]*?)'
        matches = re.finditer(mixed_operators_pattern, self.source_code)
        
        for match in matches:
            expr = match.group(1).strip()
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
            
            # Check if expression has mixed AND/OR without parentheses
            if ('&&' in expr and '||' in expr) or ('&' in expr and '|' in expr):
                # Check if there are no parentheses grouping operators
                if not re.search(r'\([^()]*?(?:&&|\|\|)[^()]*?\)', expr):
                    self.patterns.append({
                        "type": "operator_precedence",
                        "location": line_num,
                        "code": expr,
                        "risk_level": "high",
                        "description": "Mixed logical operators (AND/OR) without clarifying parentheses"
                    })
            
            # Check for bitwise/logical operator confusion
            if ('&' in expr and '&&' not in expr) or ('|' in expr and '||' not in expr):
                # If looks like it might be intended as logical
                if re.search(r'if\s*\([^)]*[&|][^)]*\)', expr):
                    self.patterns.append({
                        "type": "bitwise_logical_confusion",
                        "location": line_num,
                        "code": expr,
                        "risk_level": "high",
                        "description": "Possible confusion between bitwise (&, |) and logical (&&, ||) operators"
                    })
    
    def _detect_off_by_one_bugs(self):
        """Detect potential off-by-one bugs"""
        # Look for array access with hardcoded indices
        array_access_pattern = r'(\w+)\s*\[\s*([^][]+)\s*\]'
        matches = re.finditer(array_access_pattern, self.source_code)
        
        for match in matches:
            array_name = match.group(1)
            index_expr = match.group(2)
            line_num = self._get_line_number(match.start())
            
            # Check if the index is a constant or involves length
            if re.search(r'^\d+$', index_expr):
                # Fixed index - check nearby for length comparisons
                context = self._get_context(line_num, 5)
                if f"{array_name}.length" in context:
                    self.patterns.append({
                        "type": "off_by_one",
                        "location": line_num,
                        "code": f"{array_name}[{index_expr}]",
                        "risk_level": "medium",
                        "description": f"Hardcoded array index ({index_expr}) near length check"
                    })
                    
            # Look for .length - 1 or .length comparisons
            elif ".length - 1" in index_expr or ".length" in index_expr:
                self.patterns.append({
                    "type": "off_by_one",
                    "location": line_num,
                    "code": f"{array_name}[{index_expr}]",
                    "risk_level": "medium",
                    "description": "Array access using length expression, potential off-by-one"
                })
        
        # Look for loop boundary conditions
        loop_patterns = [
            r'for\s*\([^;]*;\s*(\w+)\s*([<>=!]+)\s*([^;]+);',
            r'while\s*\(\s*(\w+)\s*([<>=!]+)\s*([^)]+)\)'
        ]
        
        for pattern in loop_patterns:
            matches = re.finditer(pattern, self.source_code)
            for match in matches:
                var_name = match.group(1)
                operator = match.group(2)
                boundary = match.group(3).strip()
                line_num = self._get_line_number(match.start())
                
                # Check for common off-by-one patterns
                if (".length" in boundary or ".size()" in boundary) and operator in ["<=", ">="]:
                    self.patterns.append({
                        "type": "off_by_one",
                        "location": line_num,
                        "code": f"{var_name} {operator} {boundary}",
                        "risk_level": "high",
                        "description": f"Potential off-by-one in loop condition using {operator} with length/size"
                    })
                elif re.search(r'\d+', boundary) and operator in ["<=", ">="]:
                    context = self._get_context(line_num, 5)
                    array_or_list_check = re.search(r'(\w+)\.(?:length|size)', context)
                    if array_or_list_check:
                        self.patterns.append({
                            "type": "off_by_one",
                            "location": line_num,
                            "code": f"{var_name} {operator} {boundary}",
                            "risk_level": "medium",
                            "description": "Loop using <= or >= with constant boundary near array/list access"
                        })
    
    def _detect_boundary_condition_bugs(self):
        """Detect potential boundary condition bugs"""
        # Look for boundary checks
        boundary_check_pattern = r'if\s*\(\s*([^)]+?)\s*([<>=!]+)\s*([^)]+?)\s*\)'
        matches = re.finditer(boundary_check_pattern, self.source_code)
        
        for match in matches:
            left = match.group(1).strip()
            operator = match.group(2)
            right = match.group(3).strip()
            line_num = self._get_line_number(match.start())
            
            # Skip if in comment
            if self._is_in_comment_or_string(match.start()):
                continue
            
            # Find checks against 0 or 1
            if right in ["0", "1"] or left in ["0", "1"]:
                # Check if there's array access nearby
                context = self._get_context(line_num, 3)
                if "[" in context and "]" in context:
                    self.patterns.append({
                        "type": "boundary_condition",
                        "location": line_num,
                        "code": f"{left} {operator} {right}",
                        "risk_level": "high",
                        "description": f"Boundary check against {right} near array access"
                    })
            
            # Check for String length or Collection size boundary checks
            if ".length()" in left or ".size()" in left or ".length" in left:
                if operator in ["==", "<=", ">="]:
                    # Check if comparing against 0
                    if right == "0":
                        self.patterns.append({
                            "type": "boundary_condition",
                            "location": line_num,
                            "code": f"{left} {operator} {right}",
                            "risk_level": "medium",
                            "description": "Empty check using length/size"
                        })
                    else:
                        self.patterns.append({
                            "type": "boundary_condition",
                            "location": line_num,
                            "code": f"{left} {operator} {right}",
                            "risk_level": "medium",
                            "description": f"Size/length comparison using {operator}"
                        })
    
    def _detect_null_handling_bugs(self):
        """Detect potential null handling bugs"""
        # Look for missing null checks before method calls
        method_call_pattern = r'(\w+)\.(\w+)\('
        matches = re.finditer(method_call_pattern, self.source_code)
        
        null_checked_vars = set()
        
        # First, find all null checks
        null_check_pattern = r'if\s*\(\s*(\w+)\s*(?:==|!=)\s*null\s*\)'
        null_checks = re.finditer(null_check_pattern, self.source_code)
        for check in null_checks:
            null_checked_vars.add(check.group(1))
        
        # Then find method calls on objects that might be null
        for match in matches:
            obj_name = match.group(1)
            method_name = match.group(2)
            line_num = self._get_line_number(match.start())
            
            # Skip primitive types and common guaranteed non-null objects
            if obj_name in ["this", "super", "String", "Integer", "Boolean", "Double", "Math"]:
                continue
                
            # Skip if we've seen a null check for this variable
            if obj_name in null_checked_vars:
                continue
            
            # Check if near parameter usage (parameters often need null checks)
            if self._is_likely_parameter(obj_name):
                self.patterns.append({
                    "type": "null_handling",
                    "location": line_num,
                    "code": f"{obj_name}.{method_name}(...)",
                    "risk_level": "high",
                    "description": f"Method call on potential parameter {obj_name} without null check"
                })
        
        # Look for nested null-safe access patterns
        nested_access_pattern = r'(\w+)\.(\w+)\.(\w+)'
        matches = re.finditer(nested_access_pattern, self.source_code)
        
        for match in matches:
            obj_name = match.group(1)
            line_num = self._get_line_number(match.start())
            
            # Skip if not nested property/method access
            if not self._is_property_access(match.group(2)):
                continue
                
            # Check if there's no null check
            if obj_name not in null_checked_vars:
                self.patterns.append({
                    "type": "null_handling",
                    "location": line_num,
                    "code": match.group(0),
                    "risk_level": "medium",
                    "description": "Nested property access without null checking intermediate results"
                })
    
    def _detect_string_comparison_bugs(self):
        """Detect potential string comparison bugs"""
        # Look for string comparison using == instead of equals()
        str_comparison_pattern = r'(\w+)\s*(==|!=)\s*(["\w]+)'
        matches = re.finditer(str_comparison_pattern, self.source_code)
        
        for match in matches:
            left = match.group(1)
            operator = match.group(2)
            right = match.group(3)
            line_num = self._get_line_number(match.start())
            
            # Skip if in comment or if comparing null
            if self._is_in_comment_or_string(match.start()) or right == "null" or left == "null":
                continue
            
            # Check if comparing string literals or variables of string type
            context = self._get_context(line_num, 5)
            if ('"' in right or 
                "String" in context and (right.isalpha() or left.isalpha())):
                self.patterns.append({
                    "type": "string_comparison",
                    "location": line_num,
                    "code": f"{left} {operator} {right}",
                    "risk_level": "high",
                    "description": f"Possible string comparison using {operator} instead of .equals()"
                })
    
    def _detect_boolean_bugs(self):
        """Detect potential boolean logic bugs"""
        # Look for complex boolean expressions
        bool_expr_pattern = r'(?:if|while)\s*\(\s*([^{};()]+?(?:&&|\|\|)[^{};()]+?)\s*\)'
        matches = re.finditer(bool_expr_pattern, self.source_code)
        
        for match in matches:
            expr = match.group(1).strip()
            line_num = self._get_line_number(match.start())
            
            # Check for potential negation issues (double negation, etc.)
            if expr.count('!') > 1:
                self.patterns.append({
                    "type": "boolean_bug",
                    "location": line_num,
                    "code": expr,
                    "risk_level": "medium",
                    "description": "Multiple negations in boolean expression, possible logic error"
                })
            
            # Check for DeMorgan's law violations - common logical mistake
            demorgan_pattern = r'!\s*\(\s*([^()]+?)\s*(?:&&|\|\|)\s*([^()]+?)\s*\)'
            if re.search(demorgan_pattern, expr):
                self.patterns.append({
                    "type": "boolean_bug",
                    "location": line_num,
                    "code": expr,
                    "risk_level": "high", 
                    "description": "Negated AND/OR expression, potential DeMorgan's Law error"
                })
            
            # Check for redundant conditions
            parts = re.split(r'&&|\|\|', expr)
            unique_parts = set(p.strip() for p in parts)
            if len(parts) != len(unique_parts):
                self.patterns.append({
                    "type": "boolean_bug",
                    "location": line_num,
                    "code": expr,
                    "risk_level": "medium",
                    "description": "Redundant conditions in boolean expression"
                })
            
            # Check for potential tautologies or contradictions
            if ('true' in expr and '||' in expr) or ('false' in expr and '&&' in expr):
                self.patterns.append({
                    "type": "boolean_bug",
                    "location": line_num,
                    "code": expr,
                    "risk_level": "medium",
                    "description": "Potential tautology or contradiction in boolean expression"
                })
    
    def _detect_resource_leaks(self):
        """Detect potential resource leak bugs"""
        # Look for resource allocations without try-with-resources
        resource_pattern = r'new\s+(FileInputStream|FileOutputStream|BufferedReader|Scanner|Connection)[\s\(]'
        matches = re.finditer(resource_pattern, self.source_code)
        
        for match in matches:
            resource_type = match.group(1)
            line_num = self._get_line_number(match.start())
            context = self._get_context(line_num, 10)
            
            # Check if it's in a try-with-resources
            if "try (" in context and ")" in context.split("try (")[1].split("{")[0]:
                continue
                
            # Check if there's a close method call
            if ".close()" not in context:
                self.patterns.append({
                    "type": "resource_leak",
                    "location": line_num,
                    "code": f"new {resource_type}(...)",
                    "risk_level": "high",
                    "description": f"Resource allocation without proper closing or try-with-resources"
                })
    
    def _detect_state_corruption_bugs(self):
        """Detect potential state corruption bugs"""
        # Look for collections being modified during iteration
        iterator_pattern = r'for\s*\(\s*(?:\w+\s+)?(\w+)\s*:\s*(\w+)\s*\)'
        matches = re.finditer(iterator_pattern, self.source_code)
        
        for match in matches:
            loop_var = match.group(1)
            collection = match.group(2)
            line_num = self._get_line_number(match.start())
            
            # Find the loop body
            loop_start = self.source_code.find("{", match.end())
            if loop_start == -1:
                continue
                
            # Find the corresponding closing brace
            depth = 1
            loop_end = loop_start + 1
            while depth > 0 and loop_end < len(self.source_code):
                if self.source_code[loop_end] == '{':
                    depth += 1
                elif self.source_code[loop_end] == '}':
                    depth -= 1
                loop_end += 1
                
            loop_body = self.source_code[loop_start:loop_end]
            
            # Check if collection is modified in the loop body
            if f"{collection}.add" in loop_body or f"{collection}.remove" in loop_body:
                self.patterns.append({
                    "type": "state_corruption",
                    "location": line_num,
                    "code": f"for ({loop_var} : {collection})",
                    "risk_level": "high",
                    "description": f"Collection {collection} modified during iteration, possible ConcurrentModificationException"
                })
    
    def _detect_integer_overflow_bugs(self):
        """Detect potential integer overflow bugs"""
        # Look for arithmetic operations on integers near MAX_VALUE
        max_value_pattern = r'(Integer\.MAX_VALUE|Long\.MAX_VALUE)'
        matches = re.finditer(max_value_pattern, self.source_code)
        
        for match in matches:
            line_num = self._get_line_number(match.start())
            context = self._get_context(line_num, 3)
            
            # Check if there's arithmetic near MAX_VALUE
            if re.search(r'[+\-*/]', context):
                self.patterns.append({
                    "type": "integer_overflow",
                    "location": line_num,
                    "code": context.split('\n')[0],
                    "risk_level": "high",
                    "description": f"Arithmetic operation near {match.group(1)}, possible overflow"
                })
        
        # Look for unchecked array allocation with large sizes
        large_array_pattern = r'new\s+\w+\[([^]]+)\]'
        matches = re.finditer(large_array_pattern, self.source_code)
        
        for match in matches:
            size_expr = match.group(1)
            line_num = self._get_line_number(match.start())
            
            # Check if the size involves arithmetic or large constants
            if re.search(r'[+\-*/]', size_expr) or re.search(r'\d{6,}', size_expr):
                self.patterns.append({
                    "type": "integer_overflow",
                    "location": line_num,
                    "code": f"new ...[{size_expr}]",
                    "risk_level": "medium",
                    "description": "Array allocation with complex size expression, possible overflow"
                })
    
    def _detect_copy_paste_bugs(self):
        """Detect potential copy-paste bugs"""
        # Look for similar consecutive lines with small differences
        lines = self.source_code.split('\n')
        for i in range(len(lines) - 1):
            if len(lines[i].strip()) < 10:  # Skip short lines
                continue
                
            current = lines[i].strip()
            next_line = lines[i+1].strip()
            
            # If lines are similar but not identical
            if current != next_line and self._similarity(current, next_line) > 0.8:
                # Find the differences
                diff_indices = [j for j in range(min(len(current), len(next_line))) if current[j] != next_line[j]]
                
                # If there are only a few differences
                if 0 < len(diff_indices) <= 5:
                    # Check if only variable names changed
                    current_diff = ''.join(current[j] for j in diff_indices if j < len(current))
                    next_diff = ''.join(next_line[j] for j in diff_indices if j < len(next_line))
                    
                    if current_diff.isalnum() and next_diff.isalnum():
                        self.patterns.append({
                            "type": "copy_paste",
                            "location": i + 1,  # 1-based line number
                            "code": f"{current}\n{next_line}",
                            "risk_level": "medium",
                            "description": "Similar consecutive lines with small differences, potential copy-paste error"
                        })
    
    def _detect_floating_point_comparison(self):
        """Detect exact floating point comparisons"""
        # Look for exact equality comparison with floats/doubles
        float_comparison_pattern = r'([^=!><]|^)(==|!=)\s*(\d+\.\d+)'
        matches = re.finditer(float_comparison_pattern, self.source_code)
        
        for match in matches:
            operator = match.group(2)
            float_value = match.group(3)
            line_num = self._get_line_number(match.start())
            
            # Skip if in comment or string
            if self._is_in_comment_or_string(match.start()):
                continue
                
            context = self._get_context(line_num, 3)
            if "float" in context or "double" in context or "Float" in context or "Double" in context:
                self.patterns.append({
                    "type": "floating_point_comparison",
                    "location": line_num,
                    "code": f"... {operator} {float_value}",
                    "risk_level": "high",
                    "description": f"Exact comparison of floating point values using {operator}"
                })
        
        # Also look for variable comparisons in floating point context
        var_comparison_pattern = r'(\w+)\s+(==|!=)\s+(\w+)'
        matches = re.finditer(var_comparison_pattern, self.source_code)
        
        for match in matches:
            var1 = match.group(1)
            operator = match.group(2)
            var2 = match.group(3)
            line_num = self._get_line_number(match.start())
            
            # Check if in a float/double context
            context = self._get_context(line_num, 5)
            if ("float" in context or "double" in context or "Float" in context or "Double" in context) and \
               not "int " in context and not "Integer" in context:
                self.patterns.append({
                    "type": "floating_point_comparison",
                    "location": line_num,
                    "code": f"{var1} {operator} {var2}",
                    "risk_level": "high",
                    "description": f"Potential exact comparison of floating point variables using {operator}"
                })
    
    def _detect_exception_handling_bugs(self):
        """Detect potential exception handling bugs"""
        # Look for empty catch blocks
        empty_catch_pattern = r'catch\s*\([^)]+\)\s*\{\s*\}'
        matches = re.finditer(empty_catch_pattern, self.source_code)
        
        for match in matches:
            line_num = self._get_line_number(match.start())
            self.patterns.append({
                "type": "exception_handling",
                "location": line_num,
                "code": match.group(0),
                "risk_level": "medium",
                "description": "Empty catch block, silently swallowing exception"
            })
        
        # Look for catch blocks that only have comments
        comment_catch_pattern = r'catch\s*\([^)]+\)\s*\{\s*(?://[^\n]*|/\*[^*]*\*/)\s*\}'
        matches = re.finditer(comment_catch_pattern, self.source_code)
        
        for match in matches:
            line_num = self._get_line_number(match.start())
            self.patterns.append({
                "type": "exception_handling",
                "location": line_num,
                "code": match.group(0),
                "risk_level": "low",
                "description": "Catch block with only comments, effectively swallowing exception"
            })
        
        # Look for catch Exception (too generic)
        generic_catch_pattern = r'catch\s*\(\s*(?:Exception|Throwable|RuntimeException)\s+'
        matches = re.finditer(generic_catch_pattern, self.source_code)
        
        for match in matches:
            line_num = self._get_line_number(match.start())
            self.patterns.append({
                "type": "exception_handling",
                "location": line_num,
                "code": match.group(0) + "...",
                "risk_level": "medium",
                "description": "Catching generic Exception/Throwable, may mask important errors"
            })
        
        # Look for throw from finally block
        throw_finally_pattern = r'finally\s*\{[^}]*throw\s+'
        matches = re.finditer(throw_finally_pattern, self.source_code)
        
        for match in matches:
            line_num = self._get_line_number(match.start())
            self.patterns.append({
                "type": "exception_handling",
                "location": line_num,
                "code": "finally { ... throw ...",
                "risk_level": "high",
                "description": "Throwing exception from finally block, may mask original exception"
            })
    
    def _detect_complex_loop_conditions(self):
        """Detect overly complex loop conditions"""
        # Look for while loops with complex conditions
        while_pattern = r'while\s*\(\s*([^{};()]+?(?:&&|\|\|)[^{};()]+?)\s*\)'
        matches = re.finditer(while_pattern, self.source_code)
        
        for match in matches:
            condition = match.group(1)
            line_num = self._get_line_number(match.start())
            
            # Count logical operators
            op_count = condition.count("&&") + condition.count("||")
            
            if op_count >= 2:
                self.patterns.append({
                    "type": "complex_loop_condition",
                    "location": line_num,
                    "code": f"while ({condition})",
                    "risk_level": "medium",
                    "description": f"Complex loop condition with {op_count} logical operators"
                })
        
        # Look for loops that update their own condition variables
        for_pattern = r'for\s*\(\s*(?:\w+\s+)?(\w+)[^;]*;\s*\1[^;]*;\s*\1\s*([+\-*/%]=|\+\+|--)'
        matches = re.finditer(for_pattern, self.source_code)
        
        for match in matches:
            var_name = match.group(1)
            update_op = match.group(2)
            line_num = self._get_line_number(match.start())
            
            # Check if the loop also updates the variable inside the body
            # (this can lead to unexpected termination or infinite loops)
            loop_start = self.source_code.find("{", match.end())
            if loop_start == -1:
                continue
                
            # Find the corresponding closing brace
            depth = 1
            loop_end = loop_start + 1
            while depth > 0 and loop_end < len(self.source_code):
                if self.source_code[loop_end] == '{':
                    depth += 1
                elif self.source_code[loop_end] == '}':
                    depth -= 1
                loop_end += 1
                
            loop_body = self.source_code[loop_start:loop_end]
            
            # Regex to find var updates like var++, var--, var += etc.
            body_updates = re.findall(f"\\b{var_name}\\s*([+\\-*/%]=|\\+\\+|--)", loop_body)
            
            if body_updates:
                self.patterns.append({
                    "type": "complex_loop_condition",
                    "location": line_num,
                    "code": f"for (...{var_name}...;...{var_name}...;...{var_name}{update_op}...)",
                    "risk_level": "high",
                    "description": f"Loop variable {var_name} updated both in loop control and loop body, potential logic error"
                })
    
    def _detect_resource_management_defects(self):
        """Detect potential resource management defects such as leaks or improper cleanup"""
        # Look for resource acquisition without proper release
        resource_patterns = [
            # File handling resources
            (r'new FileInputStream\([^)]+\)', r'\.close\(\)'),
            (r'new FileOutputStream\([^)]+\)', r'\.close\(\)'),
            (r'new FileReader\([^)]+\)', r'\.close\(\)'),
            (r'new FileWriter\([^)]+\)', r'\.close\(\)'),
            # Database connections
            (r'getConnection\([^)]+\)', r'\.close\(\)'),
            (r'createStatement\(\)', r'\.close\(\)'),
            (r'prepareStatement\([^)]+\)', r'\.close\(\)'),
            # Locks and other resources
            (r'\.lock\(\)', r'\.unlock\(\)'),
            (r'\.acquire\(\)', r'\.release\(\)')
        ]
        
        for resource_pattern, release_pattern in resource_patterns:
            resource_matches = re.finditer(resource_pattern, self.source_code)
            
            for match in resource_matches:
                line_num = self._get_line_number(match.start())
                
                # Skip if inside comments or strings
                if self._is_in_comment_or_string(match.start()):
                    continue
                
                # Check if the resource is properly released
                context_after = self.source_code[match.end():match.end()+500]
                
                # Check if there's a try-with-resources structure (Java 7+)
                try_with_resources = re.search(r'try\s*\(\s*[^)]*' + re.escape(match.group(0)), context_after)
                
                # If not in try-with-resources, check for explicit close/release
                if not try_with_resources:
                    has_release = re.search(release_pattern, context_after)
                    
                    # If no release found, report as potential resource leak
                    if not has_release:
                        self.patterns.append({
                            "type": "resource_management",
                            "subtype": "resource_leak",
                            "location": line_num,
                            "code": match.group(0),
                            "risk_level": "high",
                            "description": f"Resource acquired but not properly released: {match.group(0)}"
                        })
        
        # Check for closed resources that might be used afterward
        close_matches = re.finditer(r'(\w+)\.close\(\)', self.source_code)
        
        for match in close_matches:
            resource_var = match.group(1)
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
                
            # Check if resource is used after being closed
            context_after = self.source_code[match.end():match.end()+500]
            usage_after_close = re.search(r'\b' + re.escape(resource_var) + r'\.\w+', context_after)
            
            if usage_after_close:
                self.patterns.append({
                    "type": "resource_management",
                    "subtype": "use_after_close",
                    "location": line_num,
                    "code": f"{resource_var}.close()",
                    "risk_level": "high",
                    "description": f"Resource {resource_var} might be used after being closed"
                })
    
    def _detect_data_operation_bugs(self):
        """Detect potential data operation issues like improper conversions or manipulations"""
        # Check for risky type conversions
        risky_conversions = [
            # Integer truncation
            (r'(\w+)\s*=\s*\(int\)\s*(\w+)', "integer_truncation"),
            # Double to float loss of precision
            (r'(\w+)\s*=\s*\(float\)\s*(\w+)', "precision_loss"),
            # Long to int conversions
            (r'(\w+)\s*=\s*\(int\)\s*(\w+)\.(\w+)(?:\(\))?', "long_to_int_conversion")
        ]
        
        for pattern, issue_type in risky_conversions:
            matches = re.finditer(pattern, self.source_code)
            
            for match in matches:
                line_num = self._get_line_number(match.start())
                
                # Skip if inside comments or strings
                if self._is_in_comment_or_string(match.start()):
                    continue
                
                self.patterns.append({
                    "type": "data_operation",
                    "subtype": issue_type,
                    "location": line_num,
                    "code": match.group(0),
                    "risk_level": "medium",
                    "description": f"Potentially risky type conversion: {match.group(0)}"
                })
        
        # Check for integer division issues
        int_division_matches = re.finditer(r'(\b\d+)\s*/\s*(\b\d+)', self.source_code)
        
        for match in int_division_matches:
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
            
            # Check if this looks like integer division that should be floating point
            context = self._get_context(line_num, 2)
            if "double" in context or "float" in context:
                self.patterns.append({
                    "type": "data_operation",
                    "subtype": "integer_division",
                    "location": line_num,
                    "code": match.group(0),
                    "risk_level": "medium",
                    "description": "Integer division in floating-point context may cause precision loss"
                })
        
        # Check for signed/unsigned comparison issues
        signed_unsigned_matches = re.finditer(r'([a-zA-Z0-9_.]+)\.length\s*([<>=!]+)\s*(-\d+)', self.source_code)
        
        for match in signed_unsigned_matches:
            if match.group(3).startswith('-'):  # Negative comparison with length which is always >= 0
                line_num = self._get_line_number(match.start())
                self.patterns.append({
                    "type": "data_operation",
                    "subtype": "signed_unsigned_comparison",
                    "location": line_num,
                    "code": match.group(0),
                    "risk_level": "medium",
                    "description": "Comparison of .length (always >= 0) with negative value"
                })
    
    def _detect_concurrency_issues(self):
        """Detect potential concurrency issues such as race conditions or deadlocks"""
        # Check for shared state without synchronization
        shared_fields_pattern = r'(private|protected|public)(?:\s+static)?\s+(?!final)\s*(\w+)(?:<[^>]+>)?\s+(\w+)\s*[=;]'
        shared_fields = re.finditer(shared_fields_pattern, self.source_code)
        
        synchronized_fields = set()
        synchronized_blocks = re.finditer(r'synchronized\s*\(\s*(\w+|\bthis\b)\s*\)', self.source_code)
        
        # Collect synchronized fields
        for match in synchronized_blocks:
            synchronized_fields.add(match.group(1))
        
        for match in shared_fields:
            # Skip if field is primitive or immutable
            field_type = match.group(2)
            if field_type in ['int', 'boolean', 'char', 'byte', 'short', 'long', 'float', 'double', 'String']:
                continue
                
            field_name = match.group(3)
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
                
            # Check if field is accessed in a method without synchronization
            if 'synchronized' not in match.group(0) and field_name not in synchronized_fields:
                # Check for multiple threads accessing this field
                thread_references = re.search(r'Thread|Runnable|Callable|ExecutorService', self.source_code)
                if thread_references:
                    self.patterns.append({
                        "type": "concurrency",
                        "subtype": "unsynchronized_shared_state",
                        "location": line_num,
                        "code": match.group(0),
                        "risk_level": "high",
                        "description": f"Potentially shared mutable field '{field_name}' without proper synchronization"
                    })
        
        # Check for possible deadlocks (nested synchronized blocks)
        method_pattern = r'(?:public|protected|private)\s+[\w<>[\],\s]+\s+(\w+)\s*\([^)]*\)\s*\{([^}]+)'
        method_matches = re.finditer(method_pattern, self.source_code)
        
        for method_match in method_matches:
            method_body = method_match.group(2)
            method_name = method_match.group(1)
            
            # Find synchronized blocks
            sync_blocks = re.finditer(r'synchronized\s*\(\s*(\w+|\bthis\b)\s*\)', method_body)
            synced_objects = [m.group(1) for m in sync_blocks]
            
            # Check for nested synchronized blocks
            if len(synced_objects) > 1:
                line_num = self._get_line_number(method_match.start())
                
                self.patterns.append({
                    "type": "concurrency",
                    "subtype": "potential_deadlock",
                    "location": line_num,
                    "code": f"Method {method_name} with multiple synchronized blocks",
                    "risk_level": "high",
                    "description": f"Multiple synchronized blocks in method '{method_name}' may lead to deadlocks"
                })
    
    def _detect_error_propagation_issues(self):
        """Detect potential issues with error handling and propagation"""
        # Check for empty catch blocks
        empty_catch_pattern = r'catch\s*\([^)]+\)\s*\{\s*\}'
        empty_catches = re.finditer(empty_catch_pattern, self.source_code)
        
        for match in empty_catches:
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
                
            self.patterns.append({
                "type": "exception_handling",
                "subtype": "empty_catch",
                "location": line_num,
                "code": match.group(0),
                "risk_level": "high",
                "description": "Empty catch block swallows exception without handling"
            })
        
        # Check for exception swallowing (catch Throwable)
        catch_throwable_pattern = r'catch\s*\(\s*(Throwable|Exception)\s+\w+\s*\)'
        catch_throwables = re.finditer(catch_throwable_pattern, self.source_code)
        
        for match in catch_throwables:
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
            
            # Get context to see how it's handled
            context_after = self.source_code[match.end():match.end()+300]
            proper_handling = re.search(r'(?:throw|log|report|printStackTrace)', context_after)
            
            if not proper_handling:
                self.patterns.append({
                    "type": "exception_handling",
                    "subtype": "swallowed_exception",
                    "location": line_num,
                    "code": match.group(0),
                    "risk_level": "high",
                    "description": f"Catching {match.group(1)} without proper handling may swallow important exceptions"
                })
    def _detect_string_index_bounds_bugs(self):
        """Detect potential string index out of bounds bugs"""
        # Look for string index access and substring operations
        string_access_pattern = r'(\w+)\.charAt\(\s*([^)]+)\s*\)'
        substring_pattern = r'(\w+)\.substring\(\s*([^,)]+)(?:\s*,\s*([^)]+))?\s*\)'
        
        # Process charAt matches
        matches = re.finditer(string_access_pattern, self.source_code)
        for match in matches:
            string_var = match.group(1)
            index_expr = match.group(2)
            line_num = self._get_line_number(match.start())
            
            # Skip if in comment or string
            if self._is_in_comment_or_string(match.start()):
                continue
                
            # Check for risky index expressions
            if index_expr.isdigit() or "-" in index_expr or "+" in index_expr:
                # Fixed or calculated index - check for length checks
                context = self._get_context(line_num, 5)
                if f"{string_var}.length()" not in context:
                    self.patterns.append({
                        "type": "string_index_bounds",
                        "location": line_num,
                        "code": f"{string_var}.charAt({index_expr})",
                        "risk_level": "high",
                        "description": f"String charAt() without proper length check, potential StringIndexOutOfBoundsException"
                    })
        
        # Process substring matches
        matches = re.finditer(substring_pattern, self.source_code)
        for match in matches:
            string_var = match.group(1)
            start_idx = match.group(2)
            end_idx = match.group(3)  # Could be None for single-arg substring
            line_num = self._get_line_number(match.start())
            
            # Skip if in comment or string
            if self._is_in_comment_or_string(match.start()):
                continue
                
            # Check for risky substring operations
            if (start_idx.isdigit() or "-" in start_idx or "+" in start_idx or 
                (end_idx and (end_idx.isdigit() or "-" in end_idx or "+" in end_idx))):
                # Check for length validation
                context = self._get_context(line_num, 5)
                if f"{string_var}.length()" not in context:
                    self.patterns.append({
                        "type": "string_index_bounds",
                        "location": line_num,
                        "code": f"{string_var}.substring({start_idx}{', ' + end_idx if end_idx else ''})",
                        "risk_level": "high",
                        "description": f"String substring() without proper length check, potential StringIndexOutOfBoundsException"
                    })
        
        # Look for direct string index access (more in newer Java versions)
        index_access_pattern = r'(\w+)\s*\[\s*([^]]+)\s*\]'
        matches = re.finditer(index_access_pattern, self.source_code)
        for match in matches:
            var_name = match.group(1)
            index_expr = match.group(2)
            line_num = self._get_line_number(match.start())
            
            # Skip if in comment or string
            if self._is_in_comment_or_string(match.start()):
                continue
                
            # Check if this might be a string variable (based on context)
            context = self._get_context(line_num, 3)
            if "String" in context and "length()" in context:
                # Look like string index access
                if index_expr.isdigit() or "-" in index_expr or "+" in index_expr:
                    self.patterns.append({
                        "type": "string_index_bounds",
                        "location": line_num,
                        "code": f"{var_name}[{index_expr}]",
                        "risk_level": "high",
                        "description": f"Potential string index access without proper bounds check"
                    })

    def _detect_array_index_bounds_bugs(self):
        """Detect potential array index out of bounds bugs"""
        # Look for array access patterns
        array_access_pattern = r'(\w+)\s*\[\s*([^]]+)\s*\]'
        array_length_pattern = r'(\w+)\.length'
        collection_size_pattern = r'(\w+)\.size\(\)'
        
        # Track potentially risky arrays
        checked_arrays = set()  # Arrays with boundary checks
        all_arrays = set()      # All arrays accessed
        
        # Find all array length checks
        length_checks = re.finditer(array_length_pattern, self.source_code)
        for match in length_checks:
            array_name = match.group(1)
            checked_arrays.add(array_name)
        
        # Find all collection size checks
        size_checks = re.finditer(collection_size_pattern, self.source_code)
        for match in size_checks:
            collection_name = match.group(1)
            checked_arrays.add(collection_name)
        
        # Scan for array accesses
        matches = re.finditer(array_access_pattern, self.source_code)
        for match in matches:
            array_name = match.group(1)
            index_expr = match.group(2)
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
            
            # Track all array accesses
            all_arrays.add(array_name)
            
            # Check various risky patterns:
            
            # 1. Fixed indices without bounds checking
            if index_expr.isdigit() and array_name not in checked_arrays:
                # Look for context to determine if it's an array
                context = self._get_context(line_num, 3)
                if re.search(r'\[\]', context) or re.search(r'new\s+\w+\s*\[', context):
                    self.patterns.append({
                        "type": "array_index_bounds",
                        "location": line_num,
                        "code": f"{array_name}[{index_expr}]",
                        "risk_level": "medium",
                        "description": f"Array access with constant index {index_expr} without length check"
                    })
            
            # 2. Complex index expressions without bounds checking
            if ('+' in index_expr or '-' in index_expr or '*' in index_expr) and array_name not in checked_arrays:
                self.patterns.append({
                    "type": "array_index_bounds",
                    "location": line_num,
                    "code": f"{array_name}[{index_expr}]",
                    "risk_level": "high",
                    "description": f"Array access with complex index {index_expr} without length validation"
                })
                
            # 3. Variable index without bounds checking
            if re.match(r'^\w+$', index_expr) and array_name not in checked_arrays:
                # Look for context to determine if it's an array
                context = self._get_context(line_num, 5)
                # Check if index variable is validated
                if not re.search(fr'if\s*\([^)]*{index_expr}[^)]*(?:length|size)', context):
                    self.patterns.append({
                        "type": "array_index_bounds",
                        "location": line_num,
                        "code": f"{array_name}[{index_expr}]",
                        "risk_level": "medium",
                        "description": f"Array access with variable index {index_expr} without validation"
                    })
            
            # 4. Detect potential negative indices
            if ('-' in index_expr) and not re.search(r'if\s*\([^)]*' + re.escape(index_expr) + r'\s*>=\s*0', self._get_context(line_num, 5)):
                self.patterns.append({
                    "type": "array_index_bounds",
                    "location": line_num,
                    "code": f"{array_name}[{index_expr}]",
                    "risk_level": "high",
                    "description": f"Array access with potentially negative index {index_expr}"
                })
                
            # 5. Detect loop patterns that might cause off-by-one errors
            if (index_expr in self._get_context(line_num, 5) and 
                re.search(r'for\s*\([^;]*;\s*' + re.escape(index_expr) + r'\s*<=', self._get_context(line_num, 5))):
                self.patterns.append({
                    "type": "array_index_bounds",
                    "subtype": "off_by_one",
                    "location": line_num,
                    "code": f"{array_name}[{index_expr}]",
                    "risk_level": "high",
                    "description": f"Potential off-by-one error in array access with loop using <= condition"
                })
        
        # Scan source code for multi-dimensional array accesses
        multidim_pattern = r'(\w+)\s*\[\s*([^]]+)\s*\]\s*\[\s*([^]]+)\s*\]'
        multidim_matches = re.finditer(multidim_pattern, self.source_code)
        for match in multidim_matches:
            array_name = match.group(1)
            first_index = match.group(2)
            second_index = match.group(3)
            line_num = self._get_line_number(match.start())
            
            # Skip if inside comments or strings
            if self._is_in_comment_or_string(match.start()):
                continue
                
            # Check for bounds validation in surrounding code
            context = self._get_context(line_num, 5)
            has_first_dim_check = re.search(fr'if\s*\([^)]*{first_index}[^)]*{array_name}\.length', context)
            has_second_dim_check = re.search(fr'if\s*\([^)]*{second_index}[^)]*{array_name}\s*\[.*\]\.length', context)
            
            # Flag if either dimension isn't checked
            if not has_first_dim_check or not has_second_dim_check:
                self.patterns.append({
                    "type": "array_index_bounds",
                    "subtype": "multidimensional",
                    "location": line_num,
                    "code": f"{array_name}[{first_index}][{second_index}]",
                    "risk_level": "high",
                    "description": "Multi-dimensional array access without complete bounds checking"
                })
        
        # Look for array iteration with potential bounds issues
        for array_name in all_arrays:
            # Find loops iterating over this array
            for_pattern = fr'for\s*\(\s*(?:int|Integer)\s+(\w+)\s*=\s*(\d+)\s*;\s*\1\s*(?:<|<=|>|>=)\s*(?:{array_name}\.length|[^;]+)\s*;\s*\1\s*(?:\+\+|--|\+=|-=)'
            for_matches = re.finditer(for_pattern, self.source_code)
            
            for match in for_matches:
                loop_var = match.group(1)
                start_val = match.group(2)
                line_num = self._get_line_number(match.start())
                loop_context = self._get_context(line_num, 10)
                
                # Check for loop patterns that might cause off-by-one errors
                if "length-1" not in loop_context and ".length-1" not in loop_context:
                    if "<=" in loop_context and ".length" in loop_context:
                        self.patterns.append({
                            "type": "array_index_bounds",
                            "subtype": "off_by_one_loop",
                            "location": line_num,
                            "code": match.group(0),
                            "risk_level": "high",
                            "description": f"Loop using <= with array length may cause off-by-one error"
                        })
                
                # Check for starting index issues
                if start_val != "0" and not re.search(fr'if\s*\([^)]*{start_val}[^)]*>=\s*0', loop_context):
                    self.patterns.append({
                        "type": "array_index_bounds",
                        "subtype": "non_zero_start",
                        "location": line_num,
                        "code": match.group(0),
                        "risk_level": "medium",
                        "description": f"Loop starts at non-zero index {start_val} without validation"
                    })

    def _detect_improper_validation(self):
        """Detect potential issues with input validation"""
        # Check for methods that accept String/Collection/array parameters without null/empty checks
        method_pattern = r'(?:public|protected|private)\s+[\w<>[\],\s]+\s+(\w+)\s*\(([^)]*)\)\s*\{'
        method_matches = re.finditer(method_pattern, self.source_code)
        
        for method_match in method_matches:
            params = method_match.group(2)
            method_name = method_match.group(1)
            
            # Look for parameters that should be validated
            param_matches = re.finditer(r'(String|List|Map|Set|Collection|Array)(?:<[^>]+>)?\s+(\w+)', params)
            
            for param_match in param_matches:
                param_type = param_match.group(1)
                param_name = param_match.group(2)
                
                # Get method body with limited context
                line_num = self._get_line_number(method_match.start())
                method_body = self._get_context(line_num, 20)  # Get reasonable context for method body
                
                # Check if there's validation
                null_check = re.search(rf'{param_name}\s*==\s*null', method_body) or re.search(r'Objects\.requireNonNull', method_body)
                empty_check = re.search(rf'{param_name}\.isEmpty\(\)', method_body) or re.search(rf'{param_name}\.length\s*==\s*0', method_body)
                
                # If no validation for String/Collection, report it
                if not null_check and (param_type in ['String', 'List', 'Map', 'Set', 'Collection']):
                    self.patterns.append({
                        "type": "validation",
                        "subtype": "missing_null_check",
                        "location": line_num,
                        "code": f"Method {method_name}, parameter {param_name}",
                        "risk_level": "medium",
                        "description": f"Parameter '{param_name}' of type '{param_type}' is not checked for null"
                    })
                
                # For collections and strings, also check for empty
                if not empty_check and param_type in ['String', 'List', 'Map', 'Set', 'Collection']:
                    self.patterns.append({
                        "type": "validation",
                        "subtype": "missing_empty_check",
                        "location": line_num,
                        "code": f"Method {method_name}, parameter {param_name}",
                        "risk_level": "medium",
                        "description": f"Parameter '{param_name}' of type '{param_type}' is not checked for empty"
                    })
    
    def _detect_security_vulnerabilities(self):
        """Detect potential security vulnerabilities"""
        # Check for hardcoded credentials
        credential_patterns = [
            (r'(?:password|passwd|pwd|secret|key)\s*=\s*"([^"]+)"', "hardcoded_password"),
            (r'(?:getConnection|DriverManager\.getConnection)\([^,]+,\s*"[^"]+",\s*"([^"]+)"', "hardcoded_db_password"),
            (r'(?:private|static)\s+(?:final)?\s*String\s+\w*(?:PASSWORD|SECRET|KEY)\w*\s*=\s*"([^"]+)"', "hardcoded_credential")
        ]
        
        for pattern, vuln_type in credential_patterns:
            matches = re.finditer(pattern, self.source_code)
            
            for match in matches:
                credential = match.group(1)
                line_num = self._get_line_number(match.start())
                
                # Skip if inside comments or strings
                if self._is_in_comment_or_string(match.start()):
                    continue
                
                # Skip if obvious placeholder or test value
                if credential.lower() in ['password', 'changeme', 'test', 'example', 'placeholder']:
                    continue
                    
                self.patterns.append({
                    "type": "security",
                    "subtype": vuln_type,
                    "location": line_num,
                    "code": "Redacted for security reasons",  # Don't show the actual password in logs
                    "risk_level": "critical",
                    "description": "Hardcoded credential detected"
                })
        
        # Check for SQL injection vulnerabilities
        sql_injection_patterns = [
            r'executeQuery\(\s*"[^"]*\s*\+\s*\w+',
            r'executeUpdate\(\s*"[^"]*\s*\+\s*\w+',
            r'prepareStatement\(\s*"[^"]*\s*\+\s*\w+'
        ]
        
        for pattern in sql_injection_patterns:
            matches = re.finditer(pattern, self.source_code)
            
            for match in matches:
                line_num = self._get_line_number(match.start())
                
                # Skip if inside comments or strings
                if self._is_in_comment_or_string(match.start()):
                    continue
                    
                self.patterns.append({
                    "type": "security",
                    "subtype": "sql_injection",
                    "location": line_num,
                    "code": match.group(0),
                    "risk_level": "critical",
                    "description": "Potential SQL injection vulnerability - string concatenation in SQL query"
                })
    
    def _get_line_number(self, char_pos):
        """Convert character position to line number"""
        return self.source_code[:char_pos].count('\n') + 1
    
    def _get_context(self, line_num, radius=2):
        """Get a few lines of context around a line number"""
        start = max(0, line_num - radius - 1)
        end = min(len(self.lines), line_num + radius)
        return '\n'.join(self.lines[start:end])
    
    def _is_in_comment_or_string(self, char_pos):
        """Check if a position is inside a comment or string"""
        # Simple heuristic approach
        line_start = self.source_code.rfind('\n', 0, char_pos) + 1
        line = self.source_code[line_start:char_pos]
        
        # Check for line comment
        if '//' in line:
            return True
            
        # Check for string literal - very simplified check
        quotes = line.count('"')
        if quotes % 2 == 1:
            return True
            
        # Note: This is a simplified approach and doesn't handle all cases correctly,
        # especially block comments and escaped quotes. A proper parser would be better.
        return False
    
    def _is_likely_parameter(self, var_name):
        """Check if a variable is likely a method parameter"""
        method_pattern = fr'\w+\s+\w+\s*\([^)]*\b{var_name}\b[^)]*\)'
        return bool(re.search(method_pattern, self.source_code))
    
    def _is_property_access(self, name):
        """Check if a name is likely a property (vs method call)"""
        method_pattern = fr'\b{name}\s*\('
        return not bool(re.search(method_pattern, self.source_code))
    
    def _similarity(self, a, b):
        """Calculate simple similarity ratio between two strings"""
        if not a or not b:
            return 0
            
        shorter = min(len(a), len(b))
        longer = max(len(a), len(b))
        
        if shorter == 0:
            return 0
            
        # Count matching characters
        matched = sum(1 for i in range(shorter) if a[i] == b[i])
        return matched / longer
    
    def export_patterns(self, output_file=None):
        """
        Export the detected patterns to JSON
        
        Parameters:
        output_file (str): Path to save JSON file (optional)
        
        Returns:
        dict: Patterns organized by type
        """
        # Organize patterns by type
        patterns_by_type = defaultdict(list)
        for pattern in self.patterns:
            patterns_by_type[pattern["type"]].append(pattern)
        
        result = {
            "class_name": self.class_name,
            "package_name": self.package_name,
            "total_patterns": len(self.patterns),
            "patterns_by_type": dict(patterns_by_type),
            "patterns": self.patterns
        }
        
        if output_file:
            os.makedirs(os.path.dirname(output_file), exist_ok=True)
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(result, f, indent=2)
        
        return result
