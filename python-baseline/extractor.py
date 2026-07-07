#!/usr/bin/env python3
"""
Logic Model Extractor

This module extracts a comprehensive logical model from Java source code,
including boundary conditions, logical relationships, control flow, and
data dependencies. The model serves as a basis for logic-aware test generation.
"""

import re
import os
import json
import logging
import javalang
from collections import defaultdict

logger = logging.getLogger("f_model_extractor")

class Extractor:
    """
    Extracts logical model from Java source code including boundary conditions,
    logical operations, control flow paths, and data dependencies.
    """
    
    # 修改 Extractor 类的 __init__ 方法
    def __init__(self, source_code, class_name, package_name):
        """
        Initialize with source code to analyze
        
        Parameters:
        source_code (str): Java source code
        class_name (str): Class name
        package_name (str): Package name
        """
        self.source_code = source_code
        self.class_name = class_name
        self.package_name = package_name
        self.lines = source_code.split('\n') if source_code else []
        
        # Core logic model components
        self.boundary_conditions = []
        self.operations = []
        self.control_flow_paths = []
        self.data_dependencies = []
        self.decision_points = []
        self.nested_conditions = []
        self.branch_contexts = defaultdict(dict)
        
        # Method-related structures
        self.methods = []
        self.method_complexity = {}
        
        # Parse the source code
        self.tree = None
        try:
            # 添加对空源代码的检查
            if source_code and len(source_code.strip()) > 0:
                self.tree = javalang.parse.parse(source_code)
                if self.tree:
                    self._extract_model()
                else:
                    logger.warning(f"javalang parser returned None for {class_name}")
                    self._extract_model_with_regex()
            else:
                logger.warning(f"Empty source code provided for {class_name}")
                self._extract_model_with_regex()
        except Exception as e:
            logger.error(f"Error parsing source code: {str(e)}")
            # Fall back to regex-based analysis
            self._extract_model_with_regex()

    def _extract_model(self):
        """Extract full logic model using AST-based analysis"""
        try:
            # Check if tree is None
            if self.tree is None:
                logger.warning("AST is None, cannot perform AST-based analysis")
                return
                
            # Extract methods
            self._extract_methods()
            
            # Extract boundary conditions and logical operations
            self._extract_boundary_conditions()
            
            # Extract control flow paths
            self._extract_control_flow()
            
            # Extract data dependencies
            self._extract_data_dependencies()
            
            # Analyze decision points and nested conditions
            self._analyze_decision_points()
            
            # Compute method complexity metrics
            self._compute_method_complexity()
            
            logger.info(f"Extracted logic model for {self.package_name}.{self.class_name}: " +
                      f"{len(self.boundary_conditions)} boundary conditions, " +
                      f"{len(self.operations)} logical operations, " +
                      f"{len(self.control_flow_paths)} control paths, " +
                      f"{len(self.decision_points)} decision points")
                      
        except Exception as e:
            logger.error(f"Error extracting logic model: {str(e)}")
            # Continue with partial model
    
    def _extract_model_with_regex(self):
        """Extract basic logic model using regex when AST parsing fails"""
        try:
            # Check if source code is None or empty
            if self.source_code is None:
                logger.error("Cannot extract model: source_code is None")
                self.methods = []
                self.boundary_conditions = []
                self.operations = []
                self.decision_points = []
                return
                
            if not self.source_code.strip():
                logger.warning("Source code is empty, extracting empty model")
                self.methods = []
                self.boundary_conditions = []
                self.operations = []
                self.decision_points = []
                return
                
            # Extract method signatures
            method_pattern = r'(public|private|protected)?\s+(?:static\s+)?(?:final\s+)?(\w+(?:<[^>]+>)?)\s+(\w+)\s*\(([^)]*)\)'
            try:
                method_matches = list(re.finditer(method_pattern, self.source_code))
            except Exception as e:
                logger.error(f"Error finding method matches: {str(e)}")
                method_matches = []
            
            for match in method_matches:
                try:
                    method_name = match.group(3)
                    return_type = match.group(2)
                    self.methods.append({
                        "name": method_name,
                        "return_type": return_type,
                        "start_line": self._get_line_number(match.start())
                    })
                except Exception as e:
                    logger.error(f"Error processing method match: {str(e)}")
                    continue
            
            # Extract if conditions
            if_pattern = r'if\s*\((.*?)\)'
            try:
                if_matches = list(re.finditer(if_pattern, self.source_code))
            except Exception as e:
                logger.error(f"Error finding if matches: {str(e)}")
                if_matches = []
            
            for match in if_matches:
                try:
                    condition = match.group(1)
                    line_num = self._get_line_number(match.start())
                    
                    # Add to boundary conditions
                    self.boundary_conditions.append({
                        "condition": condition,
                        "line": line_num,
                        "type": "if_condition",
                        "method": self._find_containing_method(line_num)
                    })
                    
                    # Add to decision points
                    self.decision_points.append({
                        "type": "if",
                        "condition": condition,
                        "line": line_num,
                        "method": self._find_containing_method(line_num)
                    })
                    
                    # Extract logical operations
                    if " && " in condition or " || " in condition:
                        self.operations.append({
                            "operation": "&&" if " && " in condition else "||",
                            "condition": condition,
                            "line": line_num,
                            "method": self._find_containing_method(line_num)
                        })
                except Exception as e:
                    logger.error(f"Error processing if condition: {str(e)}")
                    continue
            
            # Extract loops (while, for)
            loop_pattern = r'(while|for)\s*\((.*?)\)'
            try:
                loop_matches = list(re.finditer(loop_pattern, self.source_code))
            except Exception as e:
                logger.error(f"Error finding loop matches: {str(e)}")
                loop_matches = []
            
            for match in loop_matches:
                try:
                    loop_type = match.group(1)
                    condition = match.group(2)
                    line_num = self._get_line_number(match.start())
                    
                    self.boundary_conditions.append({
                        "condition": condition,
                        "line": line_num,
                        "type": f"{loop_type}_loop",
                        "method": self._find_containing_method(line_num)
                    })
                    
                    self.decision_points.append({
                        "type": loop_type,
                        "condition": condition,
                        "line": line_num,
                        "method": self._find_containing_method(line_num)
                    })
                except Exception as e:
                    logger.error(f"Error processing loop: {str(e)}")
                    continue
            
            # Extract switch statements
            switch_pattern = r'switch\s*\((.*?)\)'
            try:
                switch_matches = list(re.finditer(switch_pattern, self.source_code))
            except Exception as e:
                logger.error(f"Error finding switch matches: {str(e)}")
                switch_matches = []
            
            for match in switch_matches:
                try:
                    switch_var = match.group(1)
                    line_num = self._get_line_number(match.start())
                    
                    self.decision_points.append({
                        "type": "switch",
                        "variable": switch_var,
                        "line": line_num,
                        "method": self._find_containing_method(line_num)
                    })
                except Exception as e:
                    logger.error(f"Error processing switch: {str(e)}")
                    continue
            
            logger.info(f"Extracted logic model using regex for {self.class_name}: " +
                      f"{len(self.boundary_conditions)} boundary conditions, " +
                      f"{len(self.decision_points)} decision points")
        
        except Exception as e:
            logger.error(f"Error in regex-based extraction: {str(e)}")
            # Initialize empty model on error
            self.methods = []
            self.boundary_conditions = []
            self.operations = []
            self.decision_points = []
    
    def _get_line_number(self, char_pos):
        """Convert character position to line number"""
        if self.source_code is None or not self.source_code:
            return 1
            
        line_count = 1
        for i in range(min(char_pos, len(self.source_code))):
            if self.source_code[i] == '\n':
                line_count += 1
        return line_count
    
    def _find_containing_method(self, line_num):
        """Find which method contains a given line number"""
        if not self.methods or line_num <= 0:
            return "unknown"
            
        try:
            for method in self.methods:
                # Basic guess - if line is after method start, it might be in this method
                if method.get("start_line", 0) <= line_num:
                    return method.get("name", "unknown")
        except Exception as e:
            logger.error(f"Error finding containing method: {str(e)}")
        return "unknown"
    
    def _extract_methods(self):
        """Extract method information from the AST"""
        # 检查树是否有效
        if not self.tree:
            logger.warning("Cannot extract methods: AST is None")
            return
            
        try:
            for path, node in self.tree.filter(javalang.tree.MethodDeclaration):
                if not node:
                    continue
                    
                method_info = {
                    "name": node.name if hasattr(node, 'name') else "unknown",
                    "return_type": str(node.return_type) if hasattr(node, 'return_type') and node.return_type else "void",
                    "parameters": [str(param.type) for param in node.parameters] if hasattr(node, 'parameters') and node.parameters else [],
                    "modifiers": list(node.modifiers) if hasattr(node, 'modifiers') else [],
                    "start_line": node.position.line if hasattr(node, 'position') and node.position else 0,
                    "throws": [str(exception) for exception in node.throws] if hasattr(node, 'throws') and node.throws else []
                }
                self.methods.append(method_info)
        except Exception as e:
            logger.error(f"Error in _extract_methods: {str(e)}")


    def _extract_boundary_conditions(self):
        """Extract boundary conditions and logical operations from the AST"""
        # 检查树是否有效
        if not self.tree:
            logger.warning("Cannot extract boundary conditions: AST is None")
            return
            
        try:
            # Process if statements
            if hasattr(self.tree, 'filter'):
                # Process if statements
                for path, node in self.tree.filter(javalang.tree.IfStatement):
                    if not node:
                        continue
                        
                    if not hasattr(node, 'condition') or not hasattr(node, 'position'):
                        continue
                        
                    method_name = self._get_method_from_path(path)
                    line_num = node.position.line if node.position and hasattr(node.position, 'line') else 0
                    
                    # Convert condition object to string representation
                    try:
                        condition_str = self._condition_to_string(node.condition)
                    except Exception as e:
                        logger.debug(f"Error converting condition to string: {str(e)}")
                        condition_str = "complex_condition"
                    
                    # Add boundary condition
                    self.boundary_conditions.append({
                        "condition": condition_str,
                        "line": line_num,
                        "type": "if_condition",
                        "method": method_name
                    })
                    
                    # Check for logical operations
                    if isinstance(node.condition, javalang.tree.BinaryOperation):
                        if hasattr(node.condition, 'operator'):
                            if node.condition.operator in ['&&', '||']:
                                self.operations.append({
                                    "operation": node.condition.operator,
                                    "condition": condition_str,
                                    "line": line_num,
                                    "method": method_name
                                })
                            elif node.condition.operator in ['>', '>=', '<', '<=', '==', '!=']:
                                # Add comparison operation
                                self.operations.append({
                                    "operation": node.condition.operator,
                                    "condition": condition_str,
                                    "line": line_num,
                                    "method": method_name,
                                    "is_comparison": True
                                })
                
                # Process while loops
                for path, node in self.tree.filter(javalang.tree.WhileStatement):
                    if not node:
                        continue
                        
                    if not hasattr(node, 'condition') or not hasattr(node, 'position'):
                        continue
                        
                    method_name = self._get_method_from_path(path)
                    line_num = node.position.line if node.position and hasattr(node.position, 'line') else 0
                    
                    try:
                        condition_str = self._condition_to_string(node.condition)
                    except Exception as e:
                        logger.debug(f"Error converting while condition to string: {str(e)}")
                        condition_str = "while_condition"
                    
                    self.boundary_conditions.append({
                        "condition": condition_str,
                        "line": line_num,
                        "type": "while_loop",
                        "method": method_name
                    })
                    
                    # Check for logical operations in while conditions
                    if isinstance(node.condition, javalang.tree.BinaryOperation):
                        if hasattr(node.condition, 'operator'):
                            if node.condition.operator in ['&&', '||']:
                                self.operations.append({
                                    "operation": node.condition.operator,
                                    "condition": condition_str,
                                    "line": line_num,
                                    "method": method_name
                                })
                
                # Process for loops (including enhanced for)
                for path, node in self.tree.filter(javalang.tree.ForStatement):
                    if not node:
                        continue
                        
                    if not hasattr(node, 'position'):
                        continue
                        
                    method_name = self._get_method_from_path(path)
                    line_num = node.position.line if node.position and hasattr(node.position, 'line') else 0
                    
                    # Try to extract condition from for loop control
                    control_str = "for loop control"
                    if hasattr(node, 'control'):
                        try:
                            control_str = str(node.control)
                        except Exception:
                            pass
                    
                    self.boundary_conditions.append({
                        "condition": control_str,
                        "line": line_num,
                        "type": "for_loop",
                        "method": method_name
                    })
                    
                # Process EnhancedForStatement (for-each loops)
                try:
                    for path, node in self.tree.filter(javalang.tree.EnhancedForStatement):
                        if not node or not hasattr(node, 'position'):
                            continue
                            
                        method_name = self._get_method_from_path(path)
                        line_num = node.position.line if node.position and hasattr(node.position, 'line') else 0
                        
                        iterator_str = ""
                        if hasattr(node, 'iterable'):
                            try:
                                iterator_str = self._condition_to_string(node.iterable)
                            except Exception:
                                iterator_str = "iterable"
                        
                        self.boundary_conditions.append({
                            "condition": f"for-each: {iterator_str}",
                            "line": line_num,
                            "type": "for_each_loop",
                            "method": method_name
                        })
                except Exception as e:
                    logger.debug(f"Error processing enhanced for loops: {str(e)}")
                    
                # Process do-while loops
                try:
                    for path, node in self.tree.filter(javalang.tree.DoStatement):
                        if not node or not hasattr(node, 'condition') or not hasattr(node, 'position'):
                            continue
                            
                        method_name = self._get_method_from_path(path)
                        line_num = node.position.line if node.position and hasattr(node.position, 'line') else 0
                        
                        try:
                            condition_str = self._condition_to_string(node.condition)
                        except Exception:
                            condition_str = "do-while condition"
                        
                        self.boundary_conditions.append({
                            "condition": condition_str,
                            "line": line_num,
                            "type": "do_while_loop",
                            "method": method_name
                        })
                except Exception as e:
                    logger.debug(f"Error processing do-while loops: {str(e)}")
                    
                # Process switch statements
                try:
                    for path, node in self.tree.filter(javalang.tree.SwitchStatement):
                        if not node or not hasattr(node, 'expression') or not hasattr(node, 'position'):
                            continue
                            
                        method_name = self._get_method_from_path(path)
                        line_num = node.position.line if node.position and hasattr(node.position, 'line') else 0
                        
                        try:
                            expression_str = self._condition_to_string(node.expression)
                        except Exception:
                            expression_str = "switch expression"
                        
                        self.boundary_conditions.append({
                            "condition": expression_str,
                            "line": line_num,
                            "type": "switch_statement",
                            "method": method_name
                        })
                        
                        # Process switch cases if available
                        if hasattr(node, 'cases') and node.cases:
                            for i, case in enumerate(node.cases):
                                if case and hasattr(case, 'case') and hasattr(case, 'position'):
                                    case_line = case.position.line if hasattr(case.position, 'line') else 0
                                    try:
                                        case_value = self._condition_to_string(case.case) if case.case else "default"
                                    except Exception:
                                        case_value = f"case_{i}"
                                    
                                    self.decision_points.append({
                                        "type": "switch_case",
                                        "condition": f"{expression_str} == {case_value}",
                                        "line": case_line,
                                        "method": method_name
                                    })
                except Exception as e:
                    logger.debug(f"Error processing switch statements: {str(e)}")
            else:
                logger.warning("AST tree does not have filter method")
                
        except Exception as e:
            logger.error(f"Error in _extract_boundary_conditions: {str(e)}")
            # Fall back to regex-based approach
            self._extract_boundary_conditions_with_regex()

    def _extract_control_flow(self):
        """Extract control flow paths from the AST"""
        # Build a simple representation of control flow paths
        # For each method, trace possible execution paths
        for method in self.methods:
            method_name = method["name"]
            method_body = self._get_method_body(method_name)
            
            if not method_body:
                continue
            
            # Identify basic blocks and transitions
            blocks = self._identify_basic_blocks(method_body, method_name)
            
            # Create control flow path for this method
            self.control_flow_paths.append({
                "method": method_name,
                "blocks": blocks,
                "entry": blocks[0] if blocks else None,
                "exits": [block for block in blocks if block.get("is_exit", False)]
            })
    
    def _identify_basic_blocks(self, method_body, method_name):
        """Identify basic blocks in a method body"""
        blocks = []
        
        # Simple block identification based on decision points
        current_block = {"id": 0, "lines": [], "type": "entry"}
        blocks.append(current_block)
        
        # Find all decision points in this method
        method_decisions = [d for d in self.decision_points if d["method"] == method_name]
        
        for decision in sorted(method_decisions, key=lambda d: d["line"]):
            # Close current block and start new ones
            current_block["next"] = [len(blocks)]  # True branch
            
            if decision["type"] in ["if", "while", "for"]:
                # Create block for true branch
                true_block = {"id": len(blocks), "type": "conditional_body", 
                             "condition": decision["condition"], "lines": []}
                blocks.append(true_block)
                
                # Create block for false branch (for if statements)
                if decision["type"] == "if":
                    current_block["next"].append(len(blocks))  # False branch
                    false_block = {"id": len(blocks), "type": "else_body", 
                                  "condition": f"!({decision['condition']})", "lines": []}
                    blocks.append(false_block)
            
            # Update current block
            current_block = blocks[-1]
        
        # Add exit block if needed
        if not any(block.get("is_exit", False) for block in blocks):
            exit_block = {"id": len(blocks), "type": "exit", "is_exit": True, "lines": []}
            blocks.append(exit_block)
            current_block["next"] = [exit_block["id"]]
        
        return blocks
    
    def _extract_data_dependencies(self):
        """Extract data dependencies from the AST"""
        if self.tree is None:
            logger.warning("AST is None, cannot extract data dependencies")
            return
            
        # Track variable definitions and usages
        var_defs = {}  # Maps variables to their definition locations
        var_uses = defaultdict(list)  # Maps variables to their usage locations
        
        # Track method parameters
        method_params = {}
        for method in self.methods:
            method_name = method["name"]
            method_params[method_name] = []
            
            # Extract parameters from method signature
            method_signature = self._find_method_signature(method_name)
            if method_signature:
                params = re.findall(r'(\w+)\s+(\w+)(?:,|$|\))', method_signature)
                method_params[method_name] = [param[1] for param in params]
        
        # Process variable declarations
        for path, node in self.tree.filter(javalang.tree.LocalVariableDeclaration):
            if not hasattr(node, 'declarators') or not hasattr(node, 'position'):
                continue
                
            method_name = self._get_method_from_path(path)
            line_num = node.position.line if node.position else 0
            
            for declarator in node.declarators:
                if not hasattr(declarator, 'name'):
                    continue
                    
                var_name = declarator.name
                var_defs[f"{method_name}.{var_name}"] = {
                    "line": line_num,
                    "method": method_name,
                    "initializer": self._get_initializer(declarator)
                }
        
        # Process variable usages in expressions
        for path, node in self.tree.filter(javalang.tree.MemberReference):
            if not hasattr(node, 'member') or not hasattr(node, 'position'):
                continue
                
            method_name = self._get_method_from_path(path)
            line_num = node.position.line if node.position else 0
            var_name = node.member
            
            # Check if it's a variable (vs. method call)
            if var_name in method_params.get(method_name, []) or f"{method_name}.{var_name}" in var_defs:
                var_uses[f"{method_name}.{var_name}"].append({
                    "line": line_num,
                    "method": method_name,
                    "context": self._get_context(line_num)
                })
        
        # Build data dependencies
        for var_key, def_info in var_defs.items():
            method_name, var_name = var_key.split('.')
            uses = var_uses.get(var_key, [])
            
            # Look for dependencies in initializer
            init_deps = []
            initializer = def_info.get("initializer", "")
            for other_var in var_defs:
                other_method, other_var_name = other_var.split('.')
                if other_method == method_name and other_var_name in initializer:
                    init_deps.append(other_var_name)
            
            self.data_dependencies.append({
                "variable": var_name,
                "method": method_name,
                "defined_at": def_info["line"],
                "used_at": [use["line"] for use in uses],
                "uses_count": len(uses),
                "depends_on": init_deps
            })
    
    def _analyze_decision_points(self):
        """Analyze decision points and nested conditions"""
        if self.tree is None:
            logger.warning("AST is None, cannot analyze decision points")
            return
            
        # Extract and analyze all decision points
        decision_stack = []
        current_depth = 0
        
        for path, node in self.tree.filter(javalang.tree.IfStatement):
            if not hasattr(node, 'condition') or not hasattr(node, 'position'):
                continue
                
            method_name = self._get_method_from_path(path)
            line_num = node.position.line if node.position else 0
            condition_str = self._condition_to_string(node.condition)
            
            # Track decision point
            decision = {
                "type": "if",
                "condition": condition_str,
                "line": line_num,
                "method": method_name,
                "depth": len(decision_stack)
            }
            self.decision_points.append(decision)
            
            # Update decision stack (simplified approach)
            while decision_stack and decision_stack[-1]["line"] < line_num - 10:  # Heuristic
                popped = decision_stack.pop()
            
            decision_stack.append({
                "line": line_num,
                "type": "if",
                "condition": condition_str
            })
            
            # Check for nested conditions
            if len(decision_stack) > 1:
                parent = decision_stack[-2]
                self.nested_conditions.append({
                    "parent_line": parent["line"],
                    "parent_condition": parent["condition"],
                    "child_line": line_num,
                    "child_condition": condition_str,
                    "method": method_name,
                    "nesting_level": len(decision_stack)
                })
        
        # Similar analysis for loops and other control structures...
        for path, node in self.tree.filter(javalang.tree.WhileStatement):
            if not hasattr(node, 'condition') or not hasattr(node, 'position'):
                continue
                
            method_name = self._get_method_from_path(path)
            line_num = node.position.line if node.position else 0
            condition_str = self._condition_to_string(node.condition)
            
            self.decision_points.append({
                "type": "while",
                "condition": condition_str,
                "line": line_num,
                "method": method_name,
                "depth": len(decision_stack)
            })
    
    def _compute_method_complexity(self):
        """Compute complexity metrics for each method"""
        for method in self.methods:
            method_name = method["name"]
            
            # Count decision points in this method
            decision_count = len([d for d in self.decision_points if d["method"] == method_name])
            
            # Count logical operations in this method
            logical_op_count = len([op for op in self.operations if op["method"] == method_name])
            
            # Count nested conditions
            nested_count = len([nc for nc in self.nested_conditions if nc["method"] == method_name])
            
            # Compute cyclomatic complexity (decision points + 1)
            cyclomatic = decision_count + 1
            
            # Compute cognitive complexity (weighted sum of control structures)
            cognitive = decision_count + logical_op_count + 2 * nested_count
            
            self.method_complexity[method_name] = {
                "cyclomatic": cyclomatic,
                "cognitive": cognitive,
                "decision_points": decision_count,
                "operations": logical_op_count,
                "nested_conditions": nested_count
            }
    
    def _get_method_from_path(self, path):
        """Extract method name from AST path"""
        try:
            if path:
                for node in reversed(path):
                    if isinstance(node, javalang.tree.MethodDeclaration) and hasattr(node, 'name'):
                        return node.name
        except Exception as e:
            logger.error(f"Error in _get_method_from_path: {str(e)}")
        return "unknown"
    
    def _condition_to_string(self, condition):
        """Convert condition object to string representation"""
        if condition is None:
            return ""
            
        try:
            if isinstance(condition, javalang.tree.BinaryOperation):
                left = self._condition_to_string(condition.operandl) if hasattr(condition, 'operandl') else ""
                right = self._condition_to_string(condition.operandr) if hasattr(condition, 'operandr') else ""
                op = condition.operator if hasattr(condition, 'operator') else ""
                return f"{left} {op} {right}"
            elif isinstance(condition, javalang.tree.MemberReference):
                return condition.member if hasattr(condition, 'member') else "member"
            elif isinstance(condition, javalang.tree.Literal):
                return condition.value if hasattr(condition, 'value') else "value"
            else:
                return str(condition)
        except Exception as e:
            logger.error(f"Error in _condition_to_string: {str(e)}")
            return "complex_condition"
    
    def _get_method_body(self, method_name):
        """Extract method body as text"""
        if self.source_code is None or not self.source_code or not method_name:
            return ""
            
        # Simple approach using regex
        try:
            method_pattern = fr'{method_name}\s*\([^)]*\)\s*(?:throws\s+[\w,\s.]+)?\s*\{{([\s\S]*?)(?:\}}[\s\S]*?(?:public|private|protected|class|interface|enum)|\}}$)'
            match = re.search(method_pattern, self.source_code)
            if match:
                return match.group(1)
        except Exception as e:
            logger.error(f"Error extracting method body for {method_name}: {str(e)}")
        return ""
    
    def _find_method_signature(self, method_name):
        """Find method signature in source code"""
        if self.source_code is None or not self.source_code or not method_name:
            return ""
            
        try:
            pattern = fr'(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>,\s.]+\s+{method_name}\s*\([^)]*\)'
            match = re.search(pattern, self.source_code)
            if match:
                return match.group(0)
        except Exception as e:
            logger.error(f"Error finding method signature for {method_name}: {str(e)}")
        return ""
    
    def _get_initializer(self, declarator):
        """Get initializer expression as string"""
        if not hasattr(declarator, 'initializer'):
            return ""
        return str(declarator.initializer)
    
    def _get_context(self, line_num):
        """Get context around a line number"""
        if 0 <= line_num - 1 < len(self.lines):
            return self.lines[line_num - 1].strip()
        return ""
    
    def get_high_complexity_methods(self, threshold=10):
        """
        Get methods with complexity above threshold
        
        Parameters:
        threshold (int): Complexity threshold (default: 10)
        
        Returns:
        list: High-complexity methods with their metrics
        """
        return [{"name": name, **metrics} 
                for name, metrics in self.method_complexity.items()
                if metrics["cyclomatic"] > threshold or metrics["cognitive"] > threshold]
    
    def get_complex_conditions(self, min_operations=2):
        """
        Get complex logical conditions (with multiple operations)
        
        Parameters:
        min_operations (int): Minimum number of logical operations (default: 2)
        
        Returns:
        list: Complex conditions
        """
        complex_conditions = []
        
        for op in self.operations:
            condition = op["condition"]
            # Count logical operators
            op_count = condition.count("&&") + condition.count("||")
            if op_count >= min_operations:
                complex_conditions.append({
                    "condition": condition,
                    "operations": op_count,
                    "line": op["line"],
                    "method": op["method"]
                })
        
        return complex_conditions
    
    def get_deeply_nested_conditions(self, min_depth=2):
        """
        Get deeply nested conditions
        
        Parameters:
        min_depth (int): Minimum nesting depth (default: 2)
        
        Returns:
        list: Deeply nested conditions
        """
        return [cond for cond in self.nested_conditions 
                if cond["nesting_level"] >= min_depth]
    
    def get_boundary_checking_methods(self):
        """
        Get methods that perform boundary checking
        
        Returns:
        list: Methods with boundary checks
        """
        boundary_methods = set()
        for condition in self.boundary_conditions:
            # Look for comparison operators in conditions
            if (condition["condition"] and 
                any(op in condition["condition"] for op in ["<", ">", "<=", ">=", "==", "!="])):
                boundary_methods.add(condition["method"])
        
        return [{"name": method, "boundary_checks": len([c for c in self.boundary_conditions 
                                                      if c["method"] == method])}
                for method in boundary_methods]
    
    def get_risky_variable_usages(self):
        """
        Get variables with risky usage patterns
        
        Returns:
        list: Variables with risky usage patterns
        """
        risky_vars = []
        
        for dep in self.data_dependencies:
            # Check if variable is used in conditions
            used_in_conditions = False
            for cond in self.boundary_conditions:
                if dep["method"] == cond["method"] and dep["variable"] in cond["condition"]:
                    used_in_conditions = True
                    break
                    
            # Variable is used in conditions and multiple places
            if used_in_conditions and dep["uses_count"] > 1:
                risky_vars.append({
                    "variable": dep["variable"],
                    "method": dep["method"],
                    "uses": dep["uses_count"],
                    "used_in_condition": True
                })
        
        return risky_vars
    
    def get_branch_condition_context(self, max_branches=10):
        """
        Get context information for branch conditions
        
        Parameters:
        max_branches (int): Maximum number of branches to return
        
        Returns:
        dict: Branch condition contexts
        """
        branch_contexts = []
        
        for idx, condition in enumerate(self.boundary_conditions[:max_branches]):
            line_num = condition["line"]
            
            # Get context (3 lines before and after)
            context_lines = []
            for i in range(max(0, line_num - 3), min(len(self.lines), line_num + 4)):
                context_lines.append(self.lines[i - 1].strip() if 0 < i <= len(self.lines) else "")
            
            branch_contexts.append({
                "condition": condition["condition"],
                "type": condition["type"],
                "method": condition["method"],
                "line": line_num,
                "context": context_lines
            })
        
        return branch_contexts
    
    def export_model(self, output_file=None):
        """
        Export the full logic model to JSON
        
        Parameters:
        output_file (str): Path to save JSON file (optional)
        
        Returns:
        dict: Full logic model as a dictionary
        """
        model = {
            "class_name": self.class_name,
            "package_name": self.package_name,
            "methods": self.methods,
            "method_complexity": self.method_complexity,
            "boundary_conditions": self.boundary_conditions,
            "operations": self.operations,
            "decision_points": self.decision_points,
            "control_flow_paths": self.control_flow_paths,
            "data_dependencies": self.data_dependencies,
            "nested_conditions": self.nested_conditions
        }
        
        if output_file:
            os.makedirs(os.path.dirname(output_file), exist_ok=True)
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(model, f, indent=2)
        
        return model
