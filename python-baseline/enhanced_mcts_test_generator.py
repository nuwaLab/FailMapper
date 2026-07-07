#!/usr/bin/env python3
"""
Enhanced MCTS Test Generator

This module provides an enhanced Monte Carlo Tree Search (MCTS) implementation
for test generation. It serves as a base class for more specialized implementations
such as the Failure-Aware MCTS.
"""

import os
import re
import time
import random
import logging
import traceback
from collections import defaultdict

# Import modules for test validation and LLM interactions
from feedback import (
    save_test_code, generate_test_summary,read_source_code, 
    find_source_code, run_tests_with_jacoco, get_coverage_percentage, 
    call_anthropic_api, call_gpt_api, call_deepseek_api, extract_java_code
)

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("enhanced_mcts_test_generator")

class TestMethodExtractor:
    """
    Utility class for extracting test methods from test code
    """
    
    @staticmethod
    def extract_methods(test_code):
        """
        Extract test methods from the test code
        
        Parameters:
        test_code (str): Test code
        
        Returns:
        list: Extracted test methods
        """
        methods = []
        
        # Pattern to match test methods
        method_pattern = r'@Test\s+(?:@\w+\s+)*public\s+void\s+(\w+)\s*\([^)]*\)\s*(?:throws\s+[\w,\s.]+\s*)?\{([\s\S]*?)(?=\s*@Test|\s*private|\s*public|\s*protected|\s*\}[\s\n]*(?:\}|\Z))'
        
        # Find all matches
        matches = re.finditer(method_pattern, test_code)
        
        for match in matches:
            method_name = match.group(1)
            method_body = match.group(2)
            
            # Create method info
            method = {
                "name": method_name,
                "code": f"@Test\npublic void {method_name}() {{\n{method_body}\n}}",
                "body": method_body
            }
            
            methods.append(method)
            
        return methods
    
    @staticmethod
    def __init__(self):
        # Common imports needed for JUnit tests
        self.standard_imports = [
            "import org.junit.jupiter.api.Test;",
            "import org.junit.jupiter.api.BeforeEach;",
            "import org.junit.jupiter.api.AfterEach;",
            "import org.junit.jupiter.api.DisplayName;",
            "import static org.junit.jupiter.api.Assertions.*;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "import java.util.Arrays;",
            "import java.util.Properties;",
            "import java.util.Iterator;",
            "import java.util.ListIterator;",
            "import java.time.Duration;",
            "import java.nio.charset.StandardCharsets;"

        ]
        
        # Patterns for common issues
        self.issue_patterns = {
            "method_not_found": re.compile(r"cannot find symbol.*method\s+(\w+)"),
            "class_not_found": re.compile(r"cannot find symbol.*class\s+(\w+)"),
            "var_not_found": re.compile(r"cannot find symbol.*variable\s+(\w+)"),
            "private_access": re.compile(r"(\w+).*has private access"),
            "unreported_exception": re.compile(r"unreported exception\s+([\w.]+)"),
            "incompatible_types": re.compile(r"incompatible types:\s+(.*)\s+cannot be converted to\s+(.*)"),
            "repeat_method": re.compile(r"cannot find symbol.*method repeat\(int\)"),
            "missing_bracket": re.compile(r"(.*expected.*})|(.*expected.*)"),
        }
        
        # Cache of previously successful fixes
        self.fix_cache = {}
        
    def validate_and_fix(self, test_code, error_messages, class_name, package_name, source_code=None):
        """
        Validate test code and fix common issues
        
        Parameters:
        test_code (str): Test code to validate and fix
        error_messages (list): List of error messages from previous compilation
        class_name (str): Name of the class being tested
        package_name (str): Package name
        source_code (str): Source code of the class being tested
        
        Returns:
        str: Fixed test code
        """
        if not test_code or not class_name:
            return test_code
            
        # Apply cached fixes for this class if available
        cache_key = f"{package_name}.{class_name}"
        if cache_key in self.fix_cache:
            for issue, fix in self.fix_cache[cache_key].items():
                test_code = self.apply_fix(test_code, issue, fix)
        
        # Fix package and imports
        test_code = self.fix_package_and_imports(test_code, package_name)
        
        # Fix common Java syntax issues
        # test_code = self.fix_java_syntax(test_code)
        
        # Fix specific issues based on error messages
        if error_messages:
            test_code = self.fix_from_errors(test_code, error_messages, class_name, source_code)
        
        # Fix common test structure issues
        test_code = self.fix_test_structure(test_code, class_name)
        
        # Fix access modifier issues
        if source_code:
            test_code = self.fix_access_modifiers(test_code, class_name, source_code)
        
        return test_code
        
    def apply_fix(self, test_code, issue, fix):
        """Apply a specific fix to the test code"""
        if issue == "add_imports":
            for import_stmt in fix:
                if import_stmt not in test_code:
                    # Find where to add the import - after package or after existing imports
                    if "package " in test_code:
                        package_end = test_code.find(';', test_code.find("package ")) + 1
                        if "import " in test_code[:package_end + 100]:
                            # Add after last import
                            last_import = test_code.rfind(';', 0, package_end + 200)
                            test_code = test_code[:last_import+1] + "\n" + import_stmt + test_code[last_import+1:]
                        else:
                            # Add after package
                            test_code = test_code[:package_end] + "\n\n" + import_stmt + test_code[package_end:]
                    else:
                        # Add at the beginning
                        test_code = import_stmt + "\n" + test_code
        elif issue == "replace_pattern":
            pattern, replacement = fix
            test_code = re.sub(pattern, replacement, test_code)
        
        return test_code
    
    def fix_package_and_imports(self, test_code, package_name):
        """Fix package declaration and ensure necessary imports"""
        # Check and fix package declaration
        if "package " not in test_code and package_name:
            test_code = f"package {package_name};\n\n{test_code}"
        
        # Add standard imports if not present
        for import_stmt in self.standard_imports:
            if import_stmt not in test_code:
                # Find where to add imports - after package or at the beginning
                if "package " in test_code:
                    package_end = test_code.find(';', test_code.find("package ")) + 1
                    if "import " in test_code[:package_end + 100]:
                        # Don't add, as there are already imports and we might duplicate
                        pass
                    else:
                        # Add after package
                        test_code = test_code[:package_end] + "\n\n" + import_stmt + test_code[package_end:]
                else:
                    # Add at the beginning
                    test_code = import_stmt + "\n" + test_code
        
        return test_code
    
    def fix_java_syntax(self, test_code):
        """Fix common Java syntax issues"""
        # Check for missing closing braces
        open_braces = test_code.count('{')
        close_braces = test_code.count('}')
        
        if open_braces > close_braces:
            # Add missing closing braces
            test_code += "}" * (open_braces - close_braces)
            
        # Replace String.repeat with alternate implementation
        if "repeat(" in test_code:
            test_code = re.sub(r'(["\w]+)\.repeat\((\d+)\)', 
                              r'String.join("", java.util.Collections.nCopies(\2, \1))', 
                              test_code)
        
        return test_code
    
    def fix_from_errors(self, test_code, error_messages, class_name, source_code):
        """Fix issues based on specific error messages"""
        for error in error_messages:
            # Fix missing import issues
            if "cannot find symbol" in error and "class" in error:
                for pattern in ["class ListIterator", "class Arrays"]:
                    if pattern in error:
                        if pattern == "class ListIterator":
                            if "import java.util.ListIterator;" not in test_code:
                                idx = test_code.find("import ")
                                if idx >= 0:
                                    end_idx = test_code.find(";", idx) + 1
                                    test_code = test_code[:end_idx] + "\nimport java.util.ListIterator;" + test_code[end_idx:]
                                else:
                                    test_code = "import java.util.ListIterator;\n" + test_code
                        elif pattern == "class Arrays":
                            if "import java.util.Arrays;" not in test_code:
                                idx = test_code.find("import ")
                                if idx >= 0:
                                    end_idx = test_code.find(";", idx) + 1
                                    test_code = test_code[:end_idx] + "\nimport java.util.Arrays;" + test_code[end_idx:]
                                else:
                                    test_code = "import java.util.Arrays;\n" + test_code
            
            # Fix private access issues
            if "has private access" in error:
                match = self.issue_patterns["private_access"].search(error)
                if match:
                    private_method = match.group(1)
                    # Instead of trying to access private method directly, remove it
                    pattern = rf'(\s+|\.)({private_method}\s*\([^)]*\))'
                    if re.search(pattern, test_code):
                        # Find which test methods use this private method
                        test_methods_with_private = []
                        method_pattern = r'@Test[^{]*{[^}]*' + re.escape(private_method)
                        for match in re.finditer(method_pattern, test_code, re.DOTALL):
                            # Extract the method
                            method_text = test_code[match.start():match.end()]
                            method_name = re.search(r'void\s+(\w+)\s*\(', method_text)
                            if method_name:
                                test_methods_with_private.append(method_name.group(1))
                        
                        # Remove problematic test methods
                        for method_name in test_methods_with_private:
                            method_pattern = rf'@Test[^{{]*void\s+{method_name}\s*\([^{{]*{{[^}}]*}}'
                            test_code = re.sub(method_pattern, '', test_code, flags=re.DOTALL)
            
            # Fix unreported exception issues
            if "unreported exception" in error:
                match = self.issue_patterns["unreported_exception"].search(error)
                if match:
                    exception_type = match.group(1)
                    # Find methods that need to declare this exception
                    method_pattern = r'(@Test[^{]*void\s+\w+\s*\([^)]*\))\s*{'
                    test_code = re.sub(method_pattern, rf'\1 throws {exception_type} {{', test_code)
        
        return test_code
    
    def fix_test_structure(self, test_code, class_name):
        """Fix common test structure issues"""
        # Ensure the class has the right name pattern
        test_class_name = f"{class_name}Test"
        class_pattern = r'class\s+(\w+)'
        match = re.search(class_pattern, test_code)
        
        if match and match.group(1) != test_class_name:
            test_code = re.sub(class_pattern, f'class {test_class_name}', test_code)
        
        # Fix @DisplayName annotations if missing quotes
        display_name_pattern = r'@DisplayName\(([^"\'"][^)]*)\)'
        if re.search(display_name_pattern, test_code):
            test_code = re.sub(display_name_pattern, r'@DisplayName("\1")', test_code)
        
        return test_code
    
    def fix_access_modifiers(self, test_code, class_name, source_code):
        """Fix issues with access modifiers"""
        # If we don't have the source code, we can't reliably fix access modifiers
        if not source_code:
            return test_code
            
        # Find private methods in the source code
        private_methods = []
        private_pattern = r'private\s+\w+\s+(\w+)\s*\('
        for match in re.finditer(private_pattern, source_code):
            private_methods.append(match.group(1))
        
        # Find tests that try to call these private methods
        for method in private_methods:
            # Look for direct calls to private methods
            call_pattern = rf'\.{method}\s*\('
            if re.search(call_pattern, test_code):
                # Find which test methods call this private method
                test_methods_with_private = []
                method_pattern = r'@Test[^{]*{[^}]*' + re.escape(method)
                for match in re.finditer(method_pattern, test_code, re.DOTALL):
                    # Extract the method
                    method_text = test_code[match.start():match.end()]
                    method_name = re.search(r'void\s+(\w+)\s*\(', method_text)
                    if method_name:
                        test_methods_with_private.append(method_name.group(1))
                
                # Remove problematic test methods
                for method_name in test_methods_with_private:
                    method_pattern = rf'@Test[^{{]*void\s+{method_name}\s*\([^{{]*{{[^}}]*}}'
                    test_code = re.sub(method_pattern, '', test_code, flags=re.DOTALL)
        
        return test_code


class TestMethodExtractor:
    """Extracts individual test methods from a test class"""
    
    def extract_methods(self, test_code):
        """
        Extract individual test methods from test code
        
        Parameters:
        test_code (str): Full test class code
        
        Returns:
        list: List of dictionaries with method information
        """
        methods = []
        
        if not test_code:
            return methods
        
        # Look for @Test annotations followed by method declarations
        # Find all method start points
        annotation_pattern = r'(@Test[\s\S]*?)(?=@Test|$)'
        annotation_blocks = re.finditer(annotation_pattern, test_code)
        
        for block_match in annotation_blocks:
            block = block_match.group(1)
            
            # Check if this block contains a method declaration
            method_match = re.search(r'void\s+(\w+)\s*\([^)]*\)', block)
            if not method_match:
                continue
                
            method_name = method_match.group(1)
            
            # Find the opening brace for this method
            open_brace_idx = test_code.find('{', block_match.start())
            if open_brace_idx == -1:
                continue
            
            # Find the matching closing brace by counting braces
            brace_count = 1
            close_brace_idx = -1
            
            for i in range(open_brace_idx + 1, len(test_code)):
                if test_code[i] == '{':
                    brace_count += 1
                elif test_code[i] == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        close_brace_idx = i
                        break
            
            if close_brace_idx != -1:
                # Extract the complete method
                method_code = test_code[block_match.start():close_brace_idx + 1]
                methods.append({
                    'name': method_name,
                    'code': method_code
                })
        
        return methods
    
    def combine_methods(self, base_class, methods):
        """
        Combine individual test methods into a complete test class
        
        Parameters:
        base_class (str): Base test class structure (imports, class declaration, etc.)
        methods (list): List of method dictionaries
        
        Returns:
        str: Complete test class with combined methods
        """
        if not base_class or not methods:
            return base_class
            
        # Find class body
        class_match = re.search(r'(class\s+\w+\s*\{)', base_class)
        if not class_match:
            return base_class
            
        class_start = class_match.end()
        class_end = base_class.rfind('}')
        
        # Extract existing methods to avoid duplicates
        existing_methods = self.extract_methods(base_class)
        existing_names = {m.get("name", ""): True for m in existing_methods if isinstance(m, dict)}
        existing_bodies = set()
        for method in existing_methods:
            if isinstance(method, dict) and "code" in method:
                # Create simplified signature for comparison
                simplified = re.sub(r'\/\/.*?\n', '', method["code"])  # Remove comments
                simplified = re.sub(r'\s+', ' ', simplified)           # Normalize whitespace
                existing_bodies.add(simplified)
        
        # Add methods, checking for duplicates
        methods_text = "\n\n"
        added_methods = []
        
        for method in methods:
            if isinstance(method, dict) and "code" in method:
                # Get method name
                name_match = re.search(r'void\s+(\w+)\s*\(', method["code"])
                if not name_match:
                    continue
                    
                method_name = name_match.group(1)
                original_name = method_name
                
                # Check if name exists - rename if needed
                if method_name in existing_names:
                    # Try to find a unique name
                    counter = 1
                    while f"{method_name}_{counter}" in existing_names:
                        counter += 1
                        
                    new_name = f"{method_name}_{counter}"
                    # Rename method
                    method["code"] = re.sub(
                        r'void\s+' + re.escape(original_name) + r'\s*\(',
                        f'void {new_name}(',
                        method["code"]
                    )
                    method_name = new_name
                
                # Check for similar method bodies
                simplified = re.sub(r'\/\/.*?\n', '', method["code"])  # Remove comments
                simplified = re.sub(r'\s+', ' ', simplified)           # Normalize whitespace
                
                if simplified in existing_bodies:
                    continue  # Skip duplicates
                    
                # Add the method
                methods_text += method["code"] + "\n\n"
                existing_names[method_name] = True
                existing_bodies.add(simplified)
                added_methods.append(method_name)
        
        if not added_methods:
            return base_class  # No methods to add
            
        # Log which methods were added
        logger.info(f"Added {len(added_methods)} unique methods: {', '.join(added_methods)}")
        
        # If class end not found, simply append methods
        if class_end <= class_start:
            return base_class + methods_text + "\n}"
        else:
            # Insert before class closing brace
            return base_class[:class_end] + methods_text + base_class[class_end:]




def create_temp_test_class(base_test, method_code):
    """
    Create a temporary test class with the given base test and an additional method
    
    Parameters:
    base_test (str): Base test class code
    method_code (str): Method code to add
    
    Returns:
    str: Combined test class code
    """
    # Find the end of the class
    class_end = base_test.rfind('}')
    if class_end <= 0:
        return base_test
        
    # Insert method before class end
    return base_test[:class_end] + "\n\n" + method_code + "\n\n" + base_test[class_end:]


class AdaptiveMCTSNode:
    """Node in the Adaptive MCTS tree with enhanced exploration strategy"""
    
    def __init__(self, state, parent=None, action=None):
        self.state = state
        self.parent = parent
        self.action = action  # Action that led to this state
        self.children = []
        self.visits = 0
        self.value = 0.0
        self.untried_actions = None  # Will be populated after expansion
        self.potential_actions = []  # Potential actions with scores
        self.exploration_factor = 1.0  # Adaptive exploration factor
        
    def generate_possible_actions(self, test_prompt, source_code, uncovered_data=None):
        """Generate possible actions based on current state with priority scoring"""
        if not self.state.executed:
            self.state.evaluate()
                
        actions = []
        
        # If test contains nested classes, prioritize fixing that
        # if self.state.has_nested_classes:
        #     actions.append({
        #         "type": "flatten_test_structure",
        #         "description": "Refactor test to remove nested classes and use flat structure",
        #         "priority": 100  # Highest priority
        #     })
        #     self.untried_actions = actions
        #     return actions
        
        # Add actions for fixing compilation errors
        print("--------------------------------")
        print(self.state.compilation_errors)
        print("--------------------------------")

        if self.state.compilation_errors:
            actions.append({
                "type": "fix_critical_errors",
                "description": "Fix compilation errors in tests",
                "priority": 100000
            })
        
        # Add actions for improving coverage
        if self.state.uncovered_lines:
            # Sample uncovered lines with priority based on their location
            sorted_lines = sorted(self.state.uncovered_lines, key=lambda x: x['line'])
            
            # Target lines in the middle of the class first
            if source_code:
                source_lines = source_code.split('\n')
                middle_index = len(source_lines) // 2
                
                for line in sorted_lines[:5]:  # Limit to 5 to avoid too many similar actions
                    # Calculate priority based on distance from middle
                    distance_from_middle = abs(line['line'] - middle_index)
                    line_priority = 80 - min(distance_from_middle, 30)
                    
                    actions.append({
                        "type": "target_line",
                        "line": line['line'],
                        "description": f"Add test to cover line {line['line']}",
                        "priority": line_priority
                    })
                        
        if self.state.uncovered_branches:
            # Sample branches with priority based on branch location
            sorted_branches = sorted(self.state.uncovered_branches, key=lambda x: x['line'])
            
            for branch in sorted_branches[:3]:  # Limit to 3
                actions.append({
                    "type": "target_branch",
                    "line": branch['line'],
                    "description": f"Add test to cover branch at line {branch['line']}",
                    "priority": 75
                })
        
        # Action for finding bugs through edge cases
        actions.append({
            "type": "test_edge_cases",
            "description": "Add tests for edge cases that might reveal bugs",
            "priority": 60 + (len(self.state.detected_bugs) * 5)  # Higher priority if already found bugs
        })
        
        # Action for detecting resource issues
        actions.append({
            "type": "test_for_resource_issues",
            "description": "Test for resource leaks, infinite loops, and memory issues",
            "priority": 50 + (len(self.state.memory_errors) * 10)  # Higher priority if memory issues detected
        })
            
        # Action for investigating assertion failures
        if self.state.assertion_failures:
            actions.append({
                "type": "investigate_assertions",
                "description": "Investigate and refine assertion failures that may indicate real bugs",
                "priority": 70
            })
            
        # Action for bug hunting
        actions.append({
            "type": "hunt_bugs",
            "description": "Focus on finding bugs (edge cases, invariants)",
            "priority": 55
        })

        actions.append({
            "type": "test_numerical_precision",
            "description": "Test mathematical functions with different precision settings",
            "priority": 65
        })

        actions.append({
            "type": "test_with_special_chars",
            "description": "Add tests with special characters (CJK, emoji, control chars)",
            "priority": 65
        })
        
        actions.append({
            "type": "test_with_numeric_edge_cases",
            "description": "Add tests with numeric edge cases (negative, max values, precision limits)",
            "priority": 60
        })
        
        actions.append({
            "type": "test_with_multiple_iterators",
            "description": "Add tests that use multiple iterators on the same data source",
            "priority": 70
        })
        
        # NEW: Add format compatibility testing
        actions.append({
            "type": "test_format_compatibility",
            "description": "Test format compatibility with standard specifications (Excel, etc.)",
            "priority": 68
        })
        
        # NEW: Add empty/null values testing
        actions.append({
            "type": "test_empty_null_values",
            "description": "Test behavior with empty strings, null values, and blank entries",
            "priority": 72
        })
        
        # NEW: Add file structure boundary testing
        actions.append({
            "type": "test_boundary_file_structures",
            "description": "Test boundary cases in file structures (empty lines, trailing chars)",
            "priority": 72
        })
        
        # NEW: Add permission flags testing
        actions.append({
            "type": "test_permission_flag_combinations",
            "description": "Test combinations of permission flags and special values",
            "priority": 65
        })
        
        # NEW: Add sequential operations testing
        actions.append({
            "type": "test_sequential_operations",
            "description": "Test sequences of operations that may interact (create-modify-use pattern)",
            "priority": 75
        })

        # actions.append({
        #     "type": "test_malformed_html",
        #     "description": "Test with malformed/incomplete HTML tags",
        #     "priority": 75
        # })

        actions.append({
            "type": "test_binary_data",
            "description": "Test parsing with binary data or invalid encoding",
            "priority": 80  # Higher priority to catch hanging issues
        })

        # actions.append({
        #     "type": "test_duplicate_attributes",
        #     "description": "Test HTML with duplicate attributes on elements",
        #     "priority": 70
        # })
        
        # Action for refactoring tests
        # actions.append({
        #     "type": "refactor_tests",
        #     "description": "Refactor tests for better organization and maintainability",
        #     "priority": 40
        # })
        
        # Action for improving assertions
        actions.append({
            "type": "improve_assertions",
            "description": "Improve test assertions for better validation",
            "priority": 45
        })
        
        # Add specialized actions for verified bugs
        verified_bugs = [bug for bug in self.state.detected_bugs 
                        if bug.get("verified", False) and bug.get("is_real_bug", True)]
        
        if verified_bugs:
            # Prioritize verified bugs highly
            actions.append({
                "type": "verify_bugs",
                "description": "Add tests to verify confirmed bugs, if not a bug please fix it",
                "priority": 85,
                "bugs": verified_bugs
            })
        # Handle other potential bugs for verification
        elif len(self.state.detected_bugs) > 0:
            # Get high confidence unverified bugs
            high_confidence_bugs = [bug for bug in self.state.detected_bugs 
                                if bug.get("confidence", 0) >= 0.7 and not bug.get("verified", False)]
            
            if high_confidence_bugs:
                actions.append({
                    "type": "verify_potential_bugs",
                    "description": "Verify and add tests for high-confidence potential bugs, if not a bug please fix it",
                    "priority": 75,
                    "bugs": high_confidence_bugs
                })
            else:
                actions.append({
                    "type": "verify_bugs",
                    "description": "Verify potential bugs, if not a bug please fix it",
                    "priority": 65,
                    "bugs": self.state.detected_bugs
                })
            
        # Use uncovered data to target specific uncovered code
        if uncovered_data and 'uncovered_methods' in uncovered_data:
            for method in uncovered_data['uncovered_methods'][:2]:  # Limit to 2
                actions.append({
                    "type": "target_method",
                    "method": method,
                    "description": f"Add tests to cover method: {method}",
                    "priority": 85
                })
        
        # Store potential actions with their priorities
        self.potential_actions = sorted(actions, key=lambda x: -x['priority'])
        
        # Set untried actions (copy to avoid modifying the original)
        self.untried_actions = self.potential_actions.copy()
        
        return self.potential_actions


    def is_fully_expanded(self):
        """Check if all possible actions have been tried"""
        return self.untried_actions is not None and len(self.untried_actions) == 0
        
    def best_child(self, exploration_weight=1.0):
        """Select best child node using UCB1 formula with adaptive exploration"""
        if not self.children:
            return None
            
        # Use adaptive exploration factor
        effective_exploration = exploration_weight * self.exploration_factor
        
        # UCB1 formula with node potential
        log_visits = np.log(self.visits) if self.visits > 0 else 0
        
        def ucb_score(child):
            # Base UCB1 score
            exploitation = child.value / child.visits if child.visits > 0 else 0
            exploration = effective_exploration * np.sqrt(2 * log_visits / child.visits) if child.visits > 0 else float('inf')
            
            # Add potential score component to bias toward promising states
            potential_bias = 0.1 * child.state.potential_score / 100 if hasattr(child.state, 'potential_score') else 0
            
            # Extra bonus for states with verified bugs
            verified_bugs_count = child.state.count_verified_bugs() if hasattr(child.state, 'count_verified_bugs') else 0
            verified_bonus = verified_bugs_count * 0.2
            
            # 添加新的多样性分数
            diversity_score = 0.0
            
            # 如果此状态包含特殊输入测试，增加多样性分数
            if hasattr(child.state, 'has_special_input_tests') and child.state.has_special_input_tests:
                diversity_score += 0.3
            
            # 如果此状态包含异常路径测试，增加多样性分数
            if hasattr(child.state, 'has_exception_path_tests') and child.state.has_exception_path_tests:
                diversity_score += 0.2
            
            # 组合最终分数
            return exploitation + exploration + potential_bias + verified_bonus + diversity_score
            
        return max(self.children, key=ucb_score)
        
    def add_child(self, state, action):
        """Add a child node"""
        child = AdaptiveMCTSNode(state, self, action)
        self.children.append(child)
        
        # Track and adapt exploration factor based on results
        if self.parent and self.parent.exploration_factor:
            # If this child found new verified bugs or increased coverage, reduce exploration
            # to focus more on exploitation of this promising area
            if (state.coverage > self.state.coverage + 5) or state.count_verified_bugs() > 0:
                child.exploration_factor = max(0.5, self.exploration_factor - 0.2)
            elif state.has_critical_errors():
                # If this child has critical errors, increase exploration to try different paths
                child.exploration_factor = min(2.0, self.exploration_factor + 0.3)
            else:
                # Otherwise inherit parent's exploration factor with slight randomization
                child.exploration_factor = self.exploration_factor * random.uniform(0.9, 1.1)
        
        return child
        
    def update(self, reward):
        """Update node statistics"""
        self.visits += 1
        self.value += reward
        
        # Adapt exploration factor based on reward
        if self.visits > 3:
            recent_avg = self.value / self.visits
            if recent_avg > 100:  # Very good results, reduce exploration
                self.exploration_factor = max(0.5, self.exploration_factor - 0.1)
            elif recent_avg < 0:  # Poor results, increase exploration
                self.exploration_factor = min(2.0, self.exploration_factor + 0.1)


class EnhancedMCTSTestGenerator:
    """Uses Enhanced Monte Carlo Tree Search to guide LLM test generation"""
    
    def __init__(self, project_dir, prompt_dir, class_name, package_name, 
            initial_test_code, source_code, test_prompt, 
            max_iterations=20, exploration_weight=1.0,
            use_anthropic=True, verify_bugs_mode="batch", 
            focus_on_bugs=True, initial_coverage=0.0, project_type='maven'):
        self.project_dir = project_dir
        self.prompt_dir = prompt_dir
        self.class_name = class_name
        self.package_name = package_name
        self.initial_test_code = initial_test_code
        self.source_code = source_code
        self.test_prompt = test_prompt
        self.max_iterations = max_iterations  # Increased since we're doing one tree
        self.exploration_weight = exploration_weight
        self.use_anthropic = use_anthropic
        self.verify_bugs_mode = verify_bugs_mode
        self.focus_on_bugs = focus_on_bugs
        self.initial_coverage = initial_coverage
        self.project_type = project_type
        self.critical_bug_checked = False
        
        # Create validator and method extractor
        self.validator = TestValidator()
        self.method_extractor = TestMethodExtractor()

        self.detailed_history = {
            "iterations": [],
            "coverage_trend": [],
            "bug_discoveries": [],
            "runtime_stats": [],
            "best_coverage_points": []
        }
        
        # 记录初始状态
        if initial_coverage > 0:
            self.detailed_history["coverage_trend"].append({
                "iteration": 0,
                "coverage": initial_coverage,
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
            })
            self.detailed_history["best_coverage_points"].append({
                "iteration": 0,
                "coverage": initial_coverage,
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
            })
        
        # Create initial state and evaluate
        initial_state = TestState(
            initial_test_code, 
            class_name, 
            package_name, 
            project_dir,
            source_code,
            project_type
        )
        # Initialize all bug tracking variables first
        self.all_bug_finding_tests = []
        self.bug_finding_methods = []
        self.verified_bug_methods = []

        # Track different test variants
        self.test_variants = {
            "overall": {"code": initial_test_code, "reward": 0},
            "coverage": {"code": initial_test_code, "coverage": initial_state.coverage},
            "bug_finding": {"code": None, "bug_count": 0},
            "critical_bug": {"code": None, "found": False},
            "verified_bug": {"code": None, "verified_count": 0}
        }
        
        # Track test performance metrics
        self.best_overall_test = initial_test_code
        self.best_coverage_test = initial_test_code
        self.best_bug_finding_test = None
        self.best_compilable_test = initial_test_code
        self.best_verified_bug_test = None
        
        # Explicitly evaluate initial state with bug detection
        logger.info("Evaluating initial test state with bug detection")
        initial_state.evaluate(self.validator, verify_bugs_mode == "immediate")
        self.root = AdaptiveMCTSNode(initial_state)

        # Update metrics after evaluation
        self.best_reward = self.calculate_reward(initial_state)
        self.best_coverage = initial_state.coverage
        self.best_bug_count = len(initial_state.detected_bugs)
        self.best_verified_bug_count = initial_state.count_verified_bugs()
        
        # Update test variants with initial evaluation results
        self.test_variants["overall"]["reward"] = self.best_reward
        self.test_variants["coverage"]["coverage"] = initial_state.coverage
        
        # Collect bug methods from initial test
        if initial_state.detected_bugs:
            logger.info(f"Initial test detected {len(initial_state.detected_bugs)} potential bugs")
            
            bug_methods = initial_state.get_bug_finding_test_methods()
            if bug_methods:
                self.bug_finding_methods = bug_methods
                logger.info(f"Extracted {len(bug_methods)} bug-finding methods from initial test")
            
            # Get verified bug methods
            verified_methods = initial_state.get_logical_bug_finding_methods()
            if verified_methods:
                self.verified_bug_methods = verified_methods
                logger.info(f"Found {len(verified_methods)} verified bug methods in initial test")
            
            initial_bug_info = {
                "test_code": initial_test_code,
                "bug_count": len(initial_state.detected_bugs),
                "verified_bug_count": initial_state.count_verified_bugs(),
                "coverage": initial_state.coverage,
                "iteration": 0,  # Mark as initial iteration
                "bugs": initial_state.detected_bugs,
                "execution_time": initial_state.execution_time,
                "methods": [m["code"] for m in self.bug_finding_methods] if self.bug_finding_methods else []
            }
            self.all_bug_finding_tests.append(initial_bug_info)
            logger.info(f"Added initial test to bug-finding tests collection")
            
            # Update best bug-finding test data
            if self.best_bug_finding_test is None:
                self.best_bug_finding_test = initial_test_code
                self.test_variants["bug_finding"]["code"] = initial_test_code
                self.test_variants["bug_finding"]["bug_count"] = len(initial_state.detected_bugs)
            
            # Update verified bug test data
            if verified_methods and not self.best_verified_bug_test:
                self.best_verified_bug_test = initial_test_code
                self.best_verified_bug_count = len(verified_methods)
                self.test_variants["verified_bug"]["code"] = initial_test_code
                self.test_variants["verified_bug"]["verified_count"] = len(verified_methods)
                
            # Check for critical bugs
            has_critical_bug = any(
                bug.get("type") == "critical_exception" or 
                bug.get("severity", "") == "critical" or
                bug.get("severity", "") == "high"
                for bug in initial_state.detected_bugs
            )
            
            if has_critical_bug:
                self.test_variants["critical_bug"]["code"] = initial_test_code
                self.test_variants["critical_bug"]["found"] = True
                logger.info("Initial test contains critical bugs")
        
        else:
            self.bug_finding_methods = []
            self.verified_bug_methods = []
        
        # Test complexity metrics
        self.method_count = 0
        self.assertion_count = 0
        self.line_count = 0
        
        # Additional metrics for adaptive exploration
        self.state_complexity = 0
        self.potential_score = 0
        
        # Method library for reuse
        self.method_library = {}
        
        # Uncovered code information
        self.uncovered_data = self.analyze_uncovered_code()
        
        # Track progress history
        self.history = []
        
        # Cache for successful prompts and responses
        self.prompt_cache = {}

    def analyze_uncovered_code(self):
        """Analyze source code to extract uncovered methods and regions"""
        if not self.source_code:
            return {}
            
        # Simple analysis to find method definitions
        method_pattern = r'(?:public|protected|private)\s+\w+\s+(\w+)\s*\([^)]*\)\s*(?:throws\s+[\w.]+(?:\s*,\s*[\w.]+)*\s*)?\{'
        methods = re.findall(method_pattern, self.source_code)
        
        # Todo: More sophisticated analysis using AST parsing could be added
        
        return {
            'uncovered_methods': methods,
            'source_line_count': len(self.source_code.split('\n'))
        }
        
    def run_search(self):
        """Run Enhanced MCTS algorithm with a single tree but deeper exploration"""
        logger.info("Starting Enhanced MCTS search with single tree for test generation")
        
        # Configure whether to prioritize bugs
        verify_during_mcts = self.verify_bugs_mode == "immediate"
        prioritize_bugs = self.focus_on_bugs
        
        # Set adaptive exploration parameters
        if prioritize_bugs:
            # Higher exploration factor for bug finding
            base_exploration_factor = 1.5
        else:
            base_exploration_factor = 1.0
        
        # Run deeper exploration with a single tree
        for iteration in range(self.max_iterations):
            logger.info(f"MCTS Iteration {iteration+1}/{self.max_iterations}")
            
            # Selection
            node = self.selection()
            
            # Expansion
            if not node.state.executed:
                node.state.evaluate(self.validator, verify_during_mcts, iteration)
                    
            if node.untried_actions is None:
                node.generate_possible_actions(self.test_prompt, self.source_code, self.uncovered_data)
                    
            if not node.is_fully_expanded() and node.untried_actions:
                node = self.expansion(node)
                    
            # Simulation
            reward = self.simulation(node)
            
            # Backpropagation
            self.backpropagation(node, reward)
            
            # Update best tests
            state = node.state
            
            # Adapt exploration factor based on results
            if prioritize_bugs and len(state.detected_bugs) > 0:
                # Reduce exploration to enhance convergence if bugs found
                node.exploration_factor = max(0.8, base_exploration_factor - 0.2)
            elif state.coverage > self.best_coverage + 10:
                # Reduce exploration if coverage significantly improved
                node.exploration_factor = max(0.7, base_exploration_factor - 0.3)
            else:
                # Maintain high exploration otherwise
                node.exploration_factor = base_exploration_factor
            
            # Collect bug-finding methods
            bug_methods = state.get_bug_finding_test_methods()
            if bug_methods:
                # Add iteration information to each bug method
                for method in bug_methods:
                    if isinstance(method, dict):
                        method['discovered_in_iteration'] = iteration + 1  # Store 1-indexed iteration number
                
                self.bug_finding_methods.extend(bug_methods)
                # Add bug-finding methods to library for reuse
                for method in bug_methods:
                    if isinstance(method, dict) and 'code' in method:
                        key = method.get('bug_type', 'unknown') + '_' + str(len(self.method_library))
                        self.method_library[key] = method['code']
            
            # Collect verified bug methods (if verification was done)
            verified_methods = state.get_verified_bug_finding_methods()
            if verified_methods:
                # Add iteration information to each verified bug method
                for method in verified_methods:
                    if isinstance(method, dict) and 'discovered_in_iteration' not in method:
                        method['discovered_in_iteration'] = iteration + 1  # Store 1-indexed iteration number
                
                self.verified_bug_methods.extend(verified_methods)
            
            # Update best overall test
            if reward > self.best_reward:
                self.best_overall_test = state.test_code
                self.best_reward = reward
                self.test_variants["overall"]["code"] = state.test_code
                self.test_variants["overall"]["reward"] = reward
                logger.info(f"New best overall test found: Coverage={state.coverage:.2f}%, "
                        f"Bugs={len(state.detected_bugs)}, Verified bugs={state.count_verified_bugs()}, "
                        f"Reward={reward:.2f}")
            
            # Update best coverage test (only if no critical errors)
            if not state.has_critical_errors() and state.coverage > self.best_coverage:
                self.best_coverage_test = state.test_code
                self.best_coverage = state.coverage
                self.test_variants["coverage"]["code"] = state.test_code
                self.test_variants["coverage"]["coverage"] = state.coverage
                logger.info(f"New best coverage test found: Coverage={state.coverage:.2f}%")
            
            # Update bug-finding tests within this single tree
            bug_count = len(state.detected_bugs)
            if bug_count > 0:
                # Add iteration to each bug in detected_bugs
                for bug in state.detected_bugs:
                    if isinstance(bug, dict) and 'discovered_in_iteration' not in bug:
                        bug['discovered_in_iteration'] = iteration + 1
                        
                # Save all tests that find bugs
                if not state.has_critical_errors():
                    bug_info = {
                        "test_code": state.test_code,
                        "bug_count": bug_count,
                        "verified_bug_count": state.count_verified_bugs(),
                        "coverage": state.coverage,
                        "iteration": iteration + 1,  # 1-indexed iteration number
                        "bugs": state.detected_bugs,
                        "execution_time": state.execution_time,
                        "methods": [m["code"] for m in state.get_bug_finding_test_methods()]
                    }
                    self.all_bug_finding_tests.append(bug_info)
                    
                    # Check for critical bugs
                    has_critical_bug = any(
                        bug.get("type") == "critical_exception" or 
                        bug.get("severity", "") == "critical" or
                        bug.get("severity", "") == "high"
                        for bug in state.detected_bugs
                    )
                    
                    if has_critical_bug and not self.test_variants["critical_bug"]["found"]:
                        self.test_variants["critical_bug"]["code"] = state.test_code
                        self.test_variants["critical_bug"]["found"] = True
                        logger.info(f"Found critical bug at iteration {iteration+1}")
                    
                    if bug_count > self.test_variants["bug_finding"]["bug_count"]:
                        self.test_variants["bug_finding"]["code"] = state.test_code
                        self.test_variants["bug_finding"]["bug_count"] = bug_count
                        
                    logger.info(f"Saved bug-finding test with {bug_count} bugs at iteration {iteration+1}")
                
                if bug_count > self.best_bug_count:
                    self.best_bug_finding_test = state.test_code
                    self.best_bug_count = bug_count
                    logger.info(f"New best bug-finding test found: Detected bugs={bug_count}")
            
            # Update best verified bug test
            verified_bug_count = state.count_verified_bugs()
            if verified_bug_count > 0 and not state.has_critical_errors():
                logger.info(f"Found test with {verified_bug_count} verified bugs")
                if verified_bug_count > self.best_verified_bug_count:
                    self.best_verified_bug_count = verified_bug_count
                    self.best_verified_bug_test = state.test_code
                    self.test_variants["verified_bug"]["code"] = state.test_code
                    self.test_variants["verified_bug"]["verified_count"] = verified_bug_count
                    logger.info(f"New best verified bug test found with {verified_bug_count} verified bugs")
            
            # Update best compilable test (no critical errors and best coverage)
            if not state.has_critical_errors():
                if self.best_compilable_test is None:
                    self.best_compilable_test = state.test_code
                elif state.coverage > self.get_compilable_test_coverage():
                    self.best_compilable_test = state.test_code
            
            # Record history with expanded details
            self.history.append({
                "iteration": iteration + 1,
                "action": node.action['description'] if node.action else "Initial state",
                "coverage": node.state.coverage,
                "current_best_coverage": self.best_coverage,  # Add current best coverage
                "compilation_errors": len(node.state.compilation_errors),
                "assertion_failures": len(node.state.assertion_failures),
                "detected_bugs": len(node.state.detected_bugs),
                "verified_bugs": node.state.count_verified_bugs(),
                "execution_time": node.state.execution_time,
                "reward": reward,
                "cumulative_reward": self.root.value,  # Add cumulative reward
                "best_coverage": self.best_coverage,
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
            })
            
            # Check termination conditions
            if prioritize_bugs:
                # If prioritizing bugs, stop early if enough verified bugs found
                if self.best_verified_bug_count >= 2 and iteration >= min(8, self.max_iterations - 2):
                    logger.info(f"Found {self.best_verified_bug_count} verified bugs - stopping early")
                    break
            else:
                # If prioritizing coverage, stop early if high coverage achieved
                if self.best_coverage >= 100 and iteration >= min(3, self.max_iterations - 2):
                    logger.info(f"Achieved high coverage of {self.best_coverage:.2f}% - stopping early")
                    break
        
        # After all iterations, verify all detected bugs before final selection
        if self.verify_bugs_mode == "batch" and self.bug_finding_methods:
            logger.info(f"Performing batch verification of {len(self.bug_finding_methods)} potential bug-finding methods")
            self.verified_bug_methods = self.verify_and_filter_bugs()
        
        # Save all bug finding tests information
        self.save_bug_finding_tests()
        
        # Save history to file
        history_file = os.path.join(self.project_dir, f"{self.class_name}_test_history.json")
        with open(history_file, 'w', encoding='utf-8') as f:
            json.dump(self.history, f, indent=2)
        logger.info(f"Saved MCTS search history to: {history_file}")
        
        # Select final best test
        final_best_test = self.select_final_best_test()
        final_coverage = self.best_coverage
        
        return final_best_test, final_coverage




    def get_compilable_test_coverage(self):
        """Get coverage percentage of current best compilable test"""
        if not self.best_compilable_test:
            return 0.0
            
        # Create a temporary state to evaluate coverage
        temp_state = TestState(
            self.best_compilable_test,
            self.class_name,
            self.package_name,
            self.project_dir,
            self.source_code
        )
        temp_state.evaluate(self.validator)
        return temp_state.coverage
    

    def verify_and_filter_bugs(self):
        """
        Use LLM to verify which bug-finding methods are likely to be real bugs in batch
        
        Returns:
        list: Filtered list of verified bug methods with verification results
        """
        verified_methods = []
        
        try:
            logger.info(f"Batch filtering {len(self.bug_finding_methods)} potential bug methods...")
            
            if not self.bug_finding_methods:
                return []
                
            # Check for methods that were already verified and skip them
            methods_to_verify = []
            for method in self.bug_finding_methods:
                if isinstance(method, dict) and "code" in method:
                    # If already verified, use existing results
                    if method.get("verified", False):
                        verified_methods.append(method)
                    else:
                        methods_to_verify.append(method)
                else:
                    methods_to_verify.append(method)
                    
            if not methods_to_verify:
                logger.info("All methods already verified, returning cached results")
                return verified_methods
            
            # Continue only verifying unverified methods
            logger.info(f"Verifying {len(methods_to_verify)} unverified methods")
                
            # Check for obviously incompatible method calls
            incompatible_methods = ["setValuesList", "setDeprecated", "addValuesList", 
                                "privateMethod", "inaccessible"]
            compiler_incompatible = []
            
            # Filter obviously incompatible methods
            for idx, method in enumerate(methods_to_verify):
                method_code = method["code"] if isinstance(method, dict) and "code" in method else str(method)
                
                # Check for known incompatible API calls
                for incompatible in incompatible_methods:
                    if incompatible in method_code:
                        logger.warning(f"Method {idx+1} uses incompatible API call: {incompatible}")
                        compiler_incompatible.append(idx)
                        break
                        
                # Don't exclude constructor tests unless they reference undefined symbols
                if "cannot find symbol" in method_code or "cannot resolve symbol" in method_code:
                    logger.warning(f"Method {idx+1} references undefined symbols")
                    compiler_incompatible.append(idx)
                    
            # Remove incompatible methods
            filtered_methods = [m for i, m in enumerate(methods_to_verify) if i not in compiler_incompatible]
            
            # Auto-mark common false positive patterns
            for method in filtered_methods[:]:  # Use a copy of the list for iteration
                if isinstance(method, dict) and "code" in method:
                    method_code = method["code"]
                    
                    # Check for assertion failures
                    if "expected:" in method_code and "but was:" in method_code:
                        # Mark as a verified false positive
                        method["verified"] = True
                        method["is_real_bug"] = False  # This is a false positive, not a real bug
                        method["verification_confidence"] = 0.9
                        method["verification_reasoning"] = "Assertion failure is due to mismatched expectations, not a real bug in the code."
                        # Keep the discovered_in_iteration field if it exists
                        verified_methods.append(method)
                        filtered_methods.remove(method)  # Remove from methods to verify
                        continue
                        
                    # Check for other common false positive patterns
                    if "Expected exception to be thrown" in method_code:
                        method["verified"] = True
                        method["is_real_bug"] = False
                        method["verification_confidence"] = 0.9
                        method["verification_reasoning"] = "Test expects exception that is not thrown - likely due to changed behavior."
                        # Keep the discovered_in_iteration field if it exists
                        verified_methods.append(method)
                        filtered_methods.remove(method)  # Remove from methods to verify
                        continue
            
            # If all methods have been processed, or no methods remain, return verified results
            if not filtered_methods:
                return verified_methods
                
            # Batch process remaining methods in smaller chunks to avoid LLM context limits
            batch_size = 5  # Process in batches of 5 methods at a time
            all_batches = [filtered_methods[i:i+batch_size] for i in range(0, len(filtered_methods), batch_size)]
            
            for batch_idx, batch in enumerate(all_batches):
                logger.info(f"Processing batch {batch_idx+1}/{len(all_batches)} with {len(batch)} methods")
                
                # Create prompt for LLM to verify current batch of bug methods
                prompt = f"""You are a Java testing expert. You need to analyze the following test methods to determine if they likely identify real bugs in the code under test.

    Source class: {self.package_name}.{self.class_name}

    Source code snippet:
    ```java
    {self.source_code}
    ```

    Potential bug-finding test methods:
    """
                for i, method in enumerate(batch):
                    if isinstance(method, dict) and "code" in method:
                        method_code = method["code"]
                        method_bugs = method.get("bug_info", [])
                        if method_bugs:
                            bug_info = ", ".join([bug.get("type", "Unknown") for bug in method_bugs])
                            if len(method_bugs) > 3:
                                bug_info += f", and {len(method_bugs) - 3} more"
                        else:
                            bug_info = "Unknown issue"
                            
                        prompt += f"\nMethod {i+1}:\n```java\n{method_code}\n```\n\nDetected issues: {bug_info}\n"
                    else:
                        prompt += f"\nMethod {i+1}:\n```java\n{method}\n```\n\n"
                        
                prompt += """
    For each method, determine if it's testing a real bug or potential issue in the code, rather than just a feature or expected behavior.
    Criteria for a real bug:
    - The test identifies an actual flaw, exception, or unexpected behavior
    - The behavior being tested violates the expected contract or reasonable assumptions for the class
    - It's not just testing a documented limitation or expected boundary condition

    For each method, provide:
    1. Is it likely detecting a real bug/issue? Please answer with a Yes/No
    2. A brief explanation of your reasoning
    3. A "confidence" score from 1-10 on whether this is a genuine bug

    Then provide a final list of real bugs in this exact format:
    REAL_BUGS: [comma-separated method numbers]

    For example, if methods 2, 5, and 8 are real bugs, end your response with:
    REAL_BUGS: 2, 5, 8
    """
                try:
                    # Call the LLM API
                    result = call_anthropic_api(prompt, max_tokens=4096)
                    # result = call_deepseek_api(prompt, max_tokens=4096)
                    
                    if not result or len(result) < 100:
                        logger.warning("Insufficient response from LLM for batch bug verification")
                        # Process remaining methods in batch as likely false positives
                        for method in batch:
                            if isinstance(method, dict):
                                method["verified"] = True
                                method["is_real_bug"] = False
                                method["verification_confidence"] = 0.7
                                method["verification_reasoning"] = "Automated assessment: likely false positive due to insufficient LLM response"
                                # Preserve the iteration information 
                                if "discovered_in_iteration" in method:
                                    method["discovered_in_iteration"] = method["discovered_in_iteration"]
                                verified_methods.append(method)
                        continue
                        
                    # Extract verified method numbers from the response
                    verified_indices = []
                    
                    # Look for explicit REAL_BUGS format (preferred format)
                    real_bugs_pattern = r"REAL_BUGS:\s*([\d,\s]+)"
                    real_bugs_match = re.search(real_bugs_pattern, result)
                    
                    if real_bugs_match:
                        logger.info("Found explicit REAL_BUGS format in response")
                        # Extract comma-separated numbers and convert to integers
                        numbers_text = real_bugs_match.group(1).strip()
                        numbers = re.findall(r'\d+', numbers_text)
                        for num in numbers:
                            try:
                                idx = int(num) - 1  # Convert to 0-based index
                                if 0 <= idx < len(batch):
                                    verified_indices.append(idx)
                            except ValueError:
                                continue
                    else:
                        # Fallback strategy 1: Look for "Final list" format
                        list_matches = re.findall(r"(?:- Method|Method)\s+(\d+).*?(?:real bug|REAL BUG)", result, re.IGNORECASE)
                        
                        if list_matches:
                            logger.info(f"Found {len(list_matches)} methods in 'list' format")
                            for method_num in list_matches:
                                try:
                                    idx = int(method_num) - 1
                                    if 0 <= idx < len(batch):
                                        verified_indices.append(idx)
                                except ValueError:
                                    continue
                        
                        # Fallback strategy 2: Look for Yes/No judgments
                        if not verified_indices:
                            logger.info("Attempting to extract from individual Yes/No judgments")
                            method_judgments = re.findall(
                                r"Method\s+(\d+).*?(?::|is)\s*(Yes|No|yes|no|TRUE|FALSE|True|False)",
                                result, 
                                re.IGNORECASE | re.DOTALL
                            )
                            
                            for method_num, judgment in method_judgments:
                                try:
                                    idx = int(method_num) - 1  # Convert to 0-based index
                                    if judgment.lower() in ['yes', 'true'] and 0 <= idx < len(batch):
                                        verified_indices.append(idx)
                                except ValueError:
                                    continue
                    
                    # Log the detected real bugs
                    if verified_indices:
                        verified_indices = sorted(list(set(verified_indices)))  # Remove duplicates and sort
                        logger.info(f"Detected real bugs in methods: {[i+1 for i in verified_indices]}")
                    else:
                        logger.warning("No real bugs detected in this batch")
                    
                    # Extract confidence scores for each method
                    confidence_scores = {}
                    confidence_pattern = r"Method\s+(\d+).*?[Cc]onfidence:?\s*(\d+)(?:\s*/\s*10)?"
                    confidence_matches = re.findall(confidence_pattern, result, re.IGNORECASE | re.DOTALL)
                    
                    for method_num, score in confidence_matches:
                        try:
                            idx = int(method_num) - 1  # Convert to 0-based index
                            if 0 <= idx < len(batch):
                                score_val = float(score) / 10.0  # Normalize to 0-1 scale
                                confidence_scores[idx] = score_val
                        except ValueError:
                            continue
                    
                    # Process all methods in current batch with verification results
                    for idx, method in enumerate(batch):
                        if isinstance(method, dict):
                            method_copy = method.copy()
                            method_copy["verified"] = True
                            
                            # Preserve the iteration information - ensure we keep it if it exists
                            if "discovered_in_iteration" in method:
                                method_copy["discovered_in_iteration"] = method["discovered_in_iteration"]
                            
                            # If this is identified as a real bug
                            if idx in verified_indices:
                                method_copy["is_real_bug"] = True
                                method_copy["verification_confidence"] = confidence_scores.get(idx, 0.7)
                                
                                # Extract reasoning for this method if available
                                method_pattern = r"Method\s+" + re.escape(str(idx+1)) + r".*?(?:Yes|No).*?(?:Reason(?:ing)?:|explanation)?\s*(.*?)(?=Method\s+\d+|$|REAL_BUGS:)"
                                reasoning_match = re.search(method_pattern, result, re.IGNORECASE | re.DOTALL)
                                if reasoning_match:
                                    raw_reasoning = reasoning_match.group(1).strip()
                                    # Clean up reasoning
                                    cleaned_reasoning = re.sub(r'Confidence:?\s*\d+(/10)?', '', raw_reasoning).strip()
                                    method_copy["verification_reasoning"] = cleaned_reasoning
                                else:
                                    method_copy["verification_reasoning"] = "LLM verification identified this as a real bug"
                            else:
                                # Mark as false positive
                                method_copy["is_real_bug"] = False
                                method_copy["verification_confidence"] = 1.0 - confidence_scores.get(idx, 0.3)
                                method_copy["verification_reasoning"] = "LLM verification determined this is likely a false positive"
                                
                            verified_methods.append(method_copy)
                        else:
                            # Handle non-dictionary objects
                            iteration_info = None
                            if isinstance(method, dict) and "discovered_in_iteration" in method:
                                iteration_info = method["discovered_in_iteration"]
                                
                            verified_methods.append({
                                "code": method,
                                "verified": True,
                                "is_real_bug": idx in verified_indices,
                                "verification_confidence": confidence_scores.get(idx, 0.5),
                                "bug_info": [],
                                "discovered_in_iteration": iteration_info  # Include iteration info if available
                            })
                    
                    # Add a short delay between batch requests to avoid rate limiting
                    if len(all_batches) > 1 and batch_idx < len(all_batches)-1:
                        time.sleep(1)
                    
                except Exception as e:
                    logger.error(f"Error in batch LLM verification of bugs: {str(e)}")
                    logger.error(traceback.format_exc())
                    # Process all remaining methods in batch as false positives due to error
                    for method in batch:
                        if isinstance(method, dict):
                            method["verified"] = True
                            method["is_real_bug"] = False
                            method["verification_confidence"] = 0.8
                            method["verification_reasoning"] = "Default assessment due to verification error: likely false positive"
                            # Preserve the iteration information
                            if "discovered_in_iteration" in method:
                                method["discovered_in_iteration"] = method["discovered_in_iteration"]
                            verified_methods.append(method)
            
            # Tally up verified bugs vs false positives
            verified_real_bugs = len([m for m in verified_methods if m.get("is_real_bug", False)])
            verified_false_positives = len([m for m in verified_methods if m.get("verified", False) and not m.get("is_real_bug", False)])
            
            logger.info(f"Verified {len(verified_methods)} methods: {verified_real_bugs} real bugs, {verified_false_positives} false positives")
            
            # Log iteration information for verified bugs
            for method in verified_methods:
                if method.get("is_real_bug", False) and "discovered_in_iteration" in method:
                    logger.info(f"Real bug found in iteration {method['discovered_in_iteration']} - Method: {method.get('method_name', 'unknown')}")
                    
            return verified_methods
                
        except Exception as e:
            logger.error(f"Failed to filter bug methods: {str(e)}")
            logger.error(traceback.format_exc())
            return verified_methods



    def extract_and_save_bug_methods(self, bug_finding_tests):
        """Extract and save bug finding methods from test files"""
        try:
            # Create a container for extracted methods
            extracted_methods = []
            
            # Create method extractor
            extractor = TestMethodExtractor()
            
            for test in bug_finding_tests:
                test_code = test.get("test_code", "")
                if not test_code:
                    continue
                    
                # Get bug information
                bugs = test.get("bug_findings", []) or test.get("bugs", [])
                if not bugs:
                    continue
                
                # Get the iteration this test was created in
                iteration = test.get("iteration", None)
                    
                # Extract all methods from this test
                methods = extractor.extract_methods(test_code)
                
                # Try to map methods to bugs
                for bug in bugs:
                    method_name = bug.get("test_method")
                    if not method_name:
                        continue
                        
                    # Find this method in extracted methods
                    for method in methods:
                        if isinstance(method, dict) and method.get("name") == method_name:
                            # Create bug method entry
                            bug_method = {
                                "code": method["code"],
                                "bug_type": bug.get("type", "unknown"),
                                "severity": bug.get("severity", "medium"),
                                "error": bug.get("error", ""),
                                "confidence": bug.get("confidence", 0.5),
                                "method_name": method_name,
                                "discovered_in_iteration": iteration if iteration is not None else bug.get("discovered_in_iteration")
                            }
                            extracted_methods.append(bug_method)
                            break
            
            # Save extracted methods to file if we have any
            if extracted_methods:
                bug_methods_file = os.path.join(self.project_dir, f"{self.class_name}_bug_methods.json")
                with open(bug_methods_file, 'w', encoding='utf-8') as f:
                    json.dump(extracted_methods, f, indent=2)
                logger.info(f"Saved {len(extracted_methods)} extracted bug methods to: {bug_methods_file}")
                
            return extracted_methods
            
        except Exception as e:
            logger.error(f"Error extracting bug methods: {str(e)}")
            return []



    def save_bug_finding_tests(self):
        """Save all tests that found bugs to files for reference with enhanced metadata"""
        if not self.all_bug_finding_tests:
            return
            
        try:
            # Enhance bug tests with iteration metadata
            for bug_test in self.all_bug_finding_tests:
                # Ensure each bug has iteration info
                bug_iteration = bug_test.get("iteration", 0)
                for bug in bug_test.get("bugs", []):
                    if "discovered_in_iteration" not in bug:
                        bug["discovered_in_iteration"] = bug_iteration
            
            # Save all bug tests to JSON with enhanced metadata
            bug_tests_file = os.path.join(self.project_dir, f"{self.class_name}_bug_finding_tests.json")
            with open(bug_tests_file, 'w', encoding='utf-8') as f:
                json.dump(self.all_bug_finding_tests, f, indent=2)
            logger.info(f"Saved {len(self.all_bug_finding_tests)} bug-finding tests to: {bug_tests_file}")
            
            # Save verified bug methods with iteration info
            if self.verified_bug_methods:
                # Ensure all verified bug methods have iteration information
                for method in self.verified_bug_methods:
                    if isinstance(method, dict) and "discovered_in_iteration" not in method:
                        # Try to find matching bug info in all_bug_finding_tests
                        method_name = method.get("method_name")
                        if method_name:
                            for bug_test in self.all_bug_finding_tests:
                                for bug in bug_test.get("bugs", []):
                                    if bug.get("test_method") == method_name:
                                        method["discovered_in_iteration"] = bug.get("discovered_in_iteration", bug_test.get("iteration", 0))
                                        break
                
                methods_file = os.path.join(self.project_dir, f"{self.class_name}_verified_bug_methods.json")
                with open(methods_file, 'w', encoding='utf-8') as f:
                    json.dump(self.verified_bug_methods, f, indent=2)
                logger.info(f"Saved {len(self.verified_bug_methods)} verified bug methods to: {methods_file}")
                
                # Log the iteration information for all verified bugs
                for method in self.verified_bug_methods:
                    if method.get("is_real_bug", False):
                        iteration = method.get("discovered_in_iteration", "unknown")
                        logger.info(f"Real bug method '{method.get('method_name', 'unknown')}' discovered in iteration {iteration}")
            
            # Create iteration summary for bug discovery
            bug_discovery_summary = {}
            
            for bug_test in self.all_bug_finding_tests:
                iteration = bug_test.get("iteration", 0)
                if iteration not in bug_discovery_summary:
                    bug_discovery_summary[iteration] = {
                        "total_bugs": 0,
                        "verified_bugs": 0,
                        "bug_types": {},
                        "coverage": bug_test.get("coverage", 0.0),
                        "execution_time": bug_test.get("execution_time", 0.0)
                    }
                
                bugs = bug_test.get("bugs", [])
                bug_discovery_summary[iteration]["total_bugs"] += len(bugs)
                
                # Count verified bugs
                verified_count = sum(1 for bug in bugs if bug.get("verified", False) and bug.get("is_real_bug", False))
                bug_discovery_summary[iteration]["verified_bugs"] += verified_count
                
                # Collect bug types
                for bug in bugs:
                    bug_type = bug.get("type", "unknown")
                    if bug_type not in bug_discovery_summary[iteration]["bug_types"]:
                        bug_discovery_summary[iteration]["bug_types"][bug_type] = 0
                    bug_discovery_summary[iteration]["bug_types"][bug_type] += 1
            
            # Save bug discovery summary
            discovery_summary_file = os.path.join(self.project_dir, f"{self.class_name}_bug_discovery_summary.json")
            with open(discovery_summary_file, 'w', encoding='utf-8') as f:
                json.dump(bug_discovery_summary, f, indent=2)
            logger.info(f"Saved bug discovery summary to: {discovery_summary_file}")
            
            # Identify critical bugs (high severity or long execution)
            critical_bug_tests = []
            for bug_test in self.all_bug_finding_tests:
                bugs = bug_test.get("bugs", [])
                # Check for high severity bugs
                if any(bug.get("severity", "") == "critical" or 
                    bug.get("severity", "") == "high" or
                    bug.get("type") == "critical_exception" or
                    "OutOfMemoryError" in str(bug) or
                    "StackOverflowError" in str(bug) or
                    (bug.get("verified", False) and bug.get("is_real_bug", False))
                    for bug in bugs):
                    critical_bug_tests.append(bug_test)
                # Check for abnormally long execution time
                elif bug_test.get("execution_time", 0) > 8.0:  # 8 seconds threshold
                    critical_bug_tests.append(bug_test)
            
            if critical_bug_tests:
                # Save critical bug tests JSON
                critical_tests_file = os.path.join(self.project_dir, f"{self.class_name}_critical_bug_tests.json")
                with open(critical_tests_file, 'w', encoding='utf-8') as f:
                    json.dump(critical_bug_tests, f, indent=2)
                logger.info(f"Saved {len(critical_bug_tests)} critical bug tests to: {critical_tests_file}")
                
        except Exception as e:
            logger.error(f"Failed to save bug-finding tests: {str(e)}")
            logger.error(traceback.format_exc())


    def select_final_best_test(self, merge_all=True):
        """
        选择最终最佳测试并在需要时合并所有有价值的测试方法
        
        Parameters:
        merge_all (bool): 是否合并有价值的测试方法
        
        Returns:
        str: 最终测试代码
        """
        logger.info("选择最终最佳测试")
        
        # 1. 准备所有可用的测试候选
        available_tests = []
        
        # 添加最佳覆盖率测试
        if hasattr(self, 'best_coverage_test') and self.best_coverage_test:
            available_tests.append({
                "test_code": self.best_coverage_test,
                "coverage": self.best_coverage if hasattr(self, 'best_coverage') else 0,
                "has_errors": False
            })
        
        # 添加最佳整体测试（如果与最佳覆盖率测试不同）
        if hasattr(self, 'best_overall_test') and self.best_overall_test:
            if not hasattr(self, 'best_coverage_test') or self.best_overall_test != self.best_coverage_test:
                available_tests.append({
                    "test_code": self.best_overall_test,
                    "coverage": self.best_coverage if hasattr(self, 'best_coverage') else 0,
                    "has_errors": False
                })
        
        # 添加初始测试（如果覆盖率尚可）
        if hasattr(self, 'initial_test_code') and self.initial_test_code:
            if hasattr(self, 'initial_coverage') and self.initial_coverage > 0:
                available_tests.append({
                    "test_code": self.initial_test_code,
                    "coverage": self.initial_coverage,
                    "has_errors": False
                })
        
        # 添加发现bug的测试
        bug_finding_tests = []
        if hasattr(self, 'all_bug_finding_tests') and self.all_bug_finding_tests:
            for bug_test in self.all_bug_finding_tests:
                if isinstance(bug_test, dict) and "test_code" in bug_test:
                    test_entry = {
                        "test_code": bug_test["test_code"],
                        "coverage": bug_test.get("coverage", 0),
                        "has_bug_findings": True,
                        "bug_findings": bug_test.get("bugs", []),
                    }
                    bug_finding_tests.append(test_entry)
                    available_tests.append(test_entry)
        
        # 如果没有可用测试，返回初始测试
        if not available_tests:
            logger.info("没有可用的测试候选，返回初始测试")
            return self.initial_test_code if hasattr(self, 'initial_test_code') else ""
        
        # 2. 确定基础测试（优先选择best_coverage_test）
        base_test = None
        if hasattr(self, 'best_coverage_test') and self.best_coverage_test:
            base_test = self.best_coverage_test
            logger.info("使用best_coverage_test作为基础")
        else:
            # 按覆盖率排序所有测试
            sorted_tests = sorted(available_tests, key=lambda x: x.get("coverage", 0), reverse=True)
            base_test = sorted_tests[0].get("test_code", "")
            logger.info(f"使用覆盖率最高的测试作为基础，覆盖率: {sorted_tests[0].get('coverage', 0):.2f}%")
        
        # 3. 如果不需要合并，直接返回base_test
        if not merge_all:
            return base_test
        
        # 4. 执行合并
        try:
            # 尝试使用合并函数
            merged_test = self.merge_all_valuable_tests(base_test, available_tests, bug_finding_tests)
            
            # 验证合并后的代码是否能编译
            compile_success, verified_test = self.verify_test_compilation(
                merged_test, 
                self.class_name, 
                self.package_name, 
                self.project_dir
            )
            
            if compile_success:
                logger.info("合并后的测试代码编译成功")
                return verified_test
            else:
                logger.warning("合并后的测试代码编译失败，尝试使用LLM直接修复")
                
                # 如果合并失败，尝试使用LLM直接修复base_test + verified bugs
                source_file = find_source_code(self.project_dir, self.class_name, self.package_name)
                source_code = read_source_code(source_file) if source_file else ""
                
                # 提取已验证的bug方法
                verified_bug_methods = []
                if hasattr(self, 'verified_bug_methods'):
                    verified_bug_methods = [m for m in self.verified_bug_methods 
                                        if isinstance(m, dict) and m.get("is_real_bug", True)]
                
                if verified_bug_methods and source_code:
                    # 创建一个提示，要求LLM直接合并基础测试和bug方法
                    methods_text = ""
                    for i, method in enumerate(verified_bug_methods[:5], 1):
                        methods_text += f"\n方法 {i}:\n```java\n{method.get('code', '')}\n```\n"
                    
                    special_prompt = f"""As a Java testing expert, please merge the following verified bug test methods into the base test class.
    
CRITICAL: I need the ENTIRE test class including ALL original methods, not just the fixed parts.
Your response must contain:
1. All package declarations
2. All import statements 
3. The complete class definition
4. ALL existing test methods, not just the fixed ones
5. All fields and setup methods

ABSOLUTELY FORBIDDEN SHORTCUTS:
- DO NOT use "// All existing test methods remain the same..."
- DO NOT use "// [Previous test methods continue unchanged...]"
- DO NOT use "// ... existing code ..."
- DO NOT use "// [Previous imports remain exactly the same]"
- DO NOT use ANY placeholders or comments indicating omitted code
- You MUST include ALL actual code verbatim, even if it's unchanged
- Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
- I need the complete verbatim code that can be directly saved to a file and compiled

Format your entire response as a SINGLE complete Java file that I can save and run directly.
    Base test class:
    ```java
    {base_test}
    ```

    Verified bug test methods to merge:
    {methods_text}

    Please follow these rules:
    1. Ensure all import statements are correctly merged
    2. Add any missing field declarations and initializations
    3. Rename methods to avoid conflicts
    4. Add a comment before each bug test method: // VERIFIED BUG TEST
    5. Ensure the final code can compile and run

    CRITICAL ANTI-PLACEHOLDER REQUIREMENTS:
I need the ENTIRE test class including ALL original methods, not just the fixed parts.
Your response must contain:
1. All package declarations
2. All import statements 
3. The complete class definition
4. ALL existing test methods, not just the fixed ones
5. All fields and setup methods

ABSOLUTELY FORBIDDEN SHORTCUTS:
- DO NOT use "// All existing test methods remain the same..."
- DO NOT use "// [Previous test methods continue unchanged...]"
- DO NOT use "// ... existing code ..."
- DO NOT use "// [Previous imports remain exactly the same]"
- DO NOT use ANY placeholders or comments indicating omitted code
- You MUST include ALL actual code verbatim, even if it's unchanged
- Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
- I need the complete verbatim code that can be directly saved to a file and compiled

Format your entire response as a SINGLE complete Java file that I can save and run directly.
    """
                    fixed_code = self.fix_test_with_llm(base_test, source_code, self.class_name, self.package_name, special_prompt)
                    return fixed_code
                else:
                    # 如果没有已验证的bug方法，直接返回base_test
                    logger.info("没有已验证的bug方法可合并，返回基础测试")
                    return base_test
                
        except Exception as e:
            logger.error(f"合并过程出错: {str(e)}")
            # 发生错误时，尝试使用LLM修复基础测试
            source_file = find_source_code(self.project_dir, self.class_name, self.package_name)
            if source_file:
                source_code = read_source_code(source_file)
                fixed_code = self.fix_test_with_llm(base_test, source_code, self.class_name, self.package_name)
                return fixed_code
            else:
                # 如果无法修复，返回基础测试
                return base_test
        



    def merge_all_valuable_tests(self, base_test, available_tests, bug_finding_tests):
        """
        合并有价值的测试方法到基础测试中，包括导入和变量初始化。
        如果传统方法失败，使用LLM修复代码。
        
        Parameters:
        base_test (str): 基础测试代码（通常是best_coverage_test）
        available_tests (list): 所有可用测试列表
        bug_finding_tests (list): 发现bug的测试列表
        
        Returns:
        str: 合并后的测试代码
        """
        logger.info("合并有价值的测试方法到基础测试")
        
        if not base_test:
            logger.error("基础测试代码为空，无法进行合并")
            return self.initial_test_code if hasattr(self, 'initial_test_code') else ""
        
        # 1. 获取已验证的真实bug测试方法
        verified_bug_methods = []
        if hasattr(self, 'verified_bug_methods') and self.verified_bug_methods:
            for method in self.verified_bug_methods:
                if isinstance(method, dict) and "code" in method and method.get("is_real_bug", True):
                    verified_bug_methods.append(method)
        
        # 如果没有已验证的bug方法，返回原始基础测试
        if not verified_bug_methods:
            logger.info("没有已验证的真实bug方法可合并，返回原始基础测试")
            return base_test
        
        try:
            # 简化的方法：使用类结束位置直接插入
            class_end = base_test.rfind('}')
            if class_end <= 0:
                # 如果找不到类结束位置，尝试使用LLM修复
                source_file = find_source_code(self.project_dir, self.class_name, self.package_name)
                if not source_file:
                    logger.error(f"找不到源代码文件: {self.class_name}.java")
                    return base_test
                    
                source_code = read_source_code(source_file)
                if not source_code:
                    logger.error("无法读取源代码")
                    return base_test
                    
                return self.fix_test_with_llm(base_test, source_code, self.class_name, self.package_name, 
                                            "基础测试代码结构有问题，找不到类结束位置")
            
            # 提取类声明部分
            class_pattern = r'(?:public\s+|protected\s+|private\s+)?(?:abstract\s+)?class\s+(\w+)'
            class_match = re.search(class_pattern, base_test)
            test_class_name = class_match.group(1) if class_match else f"{self.class_name}Test"
            
            # 分析包名和导入
            imports = []
            package_name = None
            
            package_match = re.search(r'package\s+([^;]+);', base_test)
            if package_match:
                package_name = package_match.group(1)
                
            import_pattern = r'import\s+([^;]+);'
            imports = re.findall(import_pattern, base_test)
            
            # 提取方法列表以避免重复
            method_extractor = TestMethodExtractor()
            base_methods = method_extractor.extract_methods(base_test)
            existing_method_names = {m.get("name", ""): True for m in base_methods if isinstance(m, dict)}
            
            # 构建已验证bug方法列表
            bug_methods_text = []
            for method in verified_bug_methods:
                method_code = method.get("code", "")
                
                # 提取方法名
                name_match = re.search(r'void\s+(\w+)\s*\(', method_code)
                if not name_match:
                    continue
                    
                method_name = name_match.group(1)
                original_name = method_name
                
                # 如果需要重命名
                if method_name in existing_method_names:
                    new_name = f"{method_name}_verified"
                    counter = 1
                    while f"{new_name}_{counter}" in existing_method_names:
                        counter += 1
                        
                    new_name = f"{new_name}_{counter}"
                    
                    # 重命名方法
                    method_code = re.sub(
                        r'(public\s+|private\s+|protected\s+)?void\s+' + re.escape(original_name) + r'\s*\(',
                        r'\1void ' + new_name + r'(',
                        method_code
                    )
                    
                    method_name = new_name
                
                # 添加方法代码，确保正确缩进
                if not method_code.startswith("    "):
                    method_code = "    " + method_code.replace("\n", "\n    ")
                    
                # 添加bug说明注释
                if not "// Verified bug" in method_code:
                    bug_type = method.get("bug_type", "unknown")
                    verification = method.get("verification_confidence", 0.8)
                    severity = method.get("severity", "medium")
                    method_code = method_code.replace("@Test", 
                        f"@Test\n    // Verified real bug test: Type: {bug_type}, Severity: {severity}, " +
                        f"Confidence: {float(verification):.2f}")
                    
                bug_methods_text.append(method_code)
                existing_method_names[method_name] = True
            
            # 构建合并后的测试代码
            merged_test = base_test[:class_end]
            
            # 添加分隔符和已验证的bug测试方法
            if bug_methods_text:
                merged_test += "\n\n    // =========================================================================\n"
                merged_test += "    // VERIFIED BUG-DETECTION TESTS\n"
                merged_test += "    // =========================================================================\n\n"
                
                merged_test += "\n\n".join(bug_methods_text)
            
            # 添加类结束括号
            merged_test += "\n}"
            
            logger.info(f"成功合并 {len(bug_methods_text)} 个已验证的bug测试方法到基础测试")
            return merged_test
            
        except Exception as e:
            logger.error(f"合并过程出错: {str(e)}")
            logger.error(traceback.format_exc())
            
            # 发生错误时，尝试使用LLM修复
            source_file = find_source_code(self.project_dir, self.class_name, self.package_name)
            if not source_file:
                logger.error(f"找不到源代码文件: {self.class_name}.java")
                return base_test
                
            source_code = read_source_code(source_file)
            if not source_code:
                logger.error("无法读取源代码")
                return base_test
            
            # 提供错误信息给LLM
            error_msg = f"合并测试时出错: {str(e)}\n{traceback.format_exc()}"
            return self.fix_test_with_llm(base_test, source_code, self.class_name, self.package_name, error_msg)
    

    def clean_test_method(self, method_code, base_test, base_imports, base_variables):
        """
        Clean a test method to remove license, package, imports and fix variable references
        
        Parameters:
        method_code (str): The test method code
        base_test (str): The base test class code
        base_imports (list): List of imports already in the base test
        base_variables (dict): Dictionary of variables and their types in base test
        
        Returns:
        tuple: (cleaned_method, required_imports, required_variables)
        """
        # Remove license header if present
        if "Licensed to the Apache Software Foundation" in method_code:
            license_end = method_code.find("*/")
            if license_end > 0:
                method_code = method_code[license_end + 2:].strip()
        
        # Remove package declaration if present
        if "package " in method_code:
            package_end = method_code.find(";", method_code.find("package ")) + 1
            method_code = method_code[package_end:].strip()
        
        # Collect and remove import statements
        required_imports = []
        import_pattern = r'import\s+([^;]+);'
        for match in re.finditer(import_pattern, method_code):
            import_stmt = match.group(0)
            import_class = match.group(1)
            required_imports.append((import_stmt, import_class))
        
        # Remove all imports from the method
        method_code = re.sub(r'import\s+[^;]+;', '', method_code).strip()
        
        # Extract just the test method itself, removing class declaration if present
        if "@Test" in method_code and "class " in method_code:
            class_start = method_code.find("class ")
            class_end = method_code.find("{", class_start) + 1
            
            # Find the test method
            test_start = method_code.find("@Test", class_end)
            if test_start > 0:
                method_code = method_code[test_start:]
        
        # Analyze for unknown variables
        required_variables = {}
        
        # Check for common test format variables
        # if "TEST_FORMAT" in method_code and "TEST_FORMAT" not in base_variables:
        #     # If TEST_FORMAT is used but not defined in base, add it
        #     required_variables["TEST_FORMAT"] = "CSVFormat"
        
        # # Check for other common CSV test variables
        # common_variables = {
        #     "CSV_INPUT": "String",
        #     "RESULT": "String",
        #     "STANDARD_COMMENTS_DISABLED": "CSVFormat",
        #     "STANDARD_COMMENTS_ENABLED": "CSVFormat",
        #     "DEFAULT_DELIMITER": "char",
        #     "DEFAULT_QUOTE": "char"
        # }
        
        # for var, var_type in common_variables.items():
        #     if var in method_code and var not in base_variables:
        #         required_variables[var] = var_type
        
        # Check for used but undefined List, Set, Map types
        if "List<" in method_code and not any("java.util.List" in imp for imp in base_imports):
            required_imports.append(("import java.util.List;", "java.util.List"))
        
        if "ArrayList<" in method_code and not any("java.util.ArrayList" in imp for imp in base_imports):
            required_imports.append(("import java.util.ArrayList;", "java.util.ArrayList"))
        
        return method_code.strip(), required_imports, required_variables


    def verify_test_compilation(self, test_code, class_name, package_name, project_dir):
        """
        验证测试代码是否能够编译，如果不能则使用LLM修复
        
        Parameters:
        test_code (str): 测试代码
        class_name (str): 类名
        package_name (str): 包名
        project_dir (str): 项目目录
        
        Returns:
        tuple: (成功标志, 修复后的代码)
        """
        logger.info("验证测试代码编译")
        
        max_attempts = 5  # 最大尝试次数
        current_test = test_code
        
        for attempt in range(max_attempts):
            # 保存并编译测试代码
            test_file_path = save_test_code(current_test, class_name, package_name, project_dir)
            if not test_file_path:
                logger.error("无法保存测试代码")
                return False, current_test
                
            # 尝试编译测试
            # success, stdout, stderr = run_maven_command("clean test", project_dir)
            coverage_data, assertion_failures, execution_time, compilation_errors = run_tests_with_jacoco(
                            project_dir, class_name, package_name, f"{package_name}.{class_name}Test", False, getattr(self, 'project_type', 'maven')
                        )
            
            # Check if compilation was successful (no errors)
            success = not compilation_errors
            
            # 检查编译结果
            if success:
                logger.info(f"测试代码编译成功 (尝试 {attempt+1}/{max_attempts})")
                return True, current_test
                
            # 编译失败，收集错误信息
            # error_message = stderr if stderr else stdout
            logger.warning(f"编译失败 (尝试 {attempt+1}/{max_attempts})")
            
            # 获取源代码以提供给LLM
            source_file = find_source_code(project_dir, class_name, package_name)
            if not source_file:
                logger.error(f"找不到源代码文件: {class_name}.java")
                return False, current_test
                
            source_code = read_source_code(source_file)
            if not source_code:
                logger.error("无法读取源代码")
                return False, current_test
            
            # 使用LLM修复代码
            fixed_code = self.fix_test_with_llm(current_test, source_code, class_name, package_name, compilation_errors)
            
            # 如果LLM未能修改代码，结束尝试
            if fixed_code == current_test:
                logger.warning("LLM无法修复代码，保持原样")
                return False, current_test
                
            # 使用修复后的代码进行下一次尝试
            current_test = fixed_code
            logger.info("使用LLM修复后的代码进行下一次尝试")
        
        # 达到最大尝试次数
        logger.warning(f"在{max_attempts}次尝试后仍无法修复编译问题")
        return False, current_test


    def fix_test_with_llm(self, test_code, source_code, class_name, package_name, error_message=None):
        """
        使用LLM修复测试代码的编译问题
        
        Parameters:
        test_code (str): 测试代码
        source_code (str): 源代码
        class_name (str): 类名
        package_name (str): 包名
        error_message (str): 错误消息，如果有的话
        
        Returns:
        str: 修复后的测试代码
        """
        logger.info("尝试使用LLM修复测试代码")
        
        # create the prompt - emphasize the clear task of the LLM and provide all the necessary context
        prompt = f"""Please help fix compilation issues in the following JUnit test code. Your task is identify undeclared variables and missing imports, and provide the complete fixed code, I need the full code, not just the fixed part.

CRITICAL ANTI-PLACEHOLDER REQUIREMENTS:
I need the ENTIRE test class including ALL original methods, not just the fixed parts.
Your response must contain:
1. All package declarations
2. All import statements 
3. The complete class definition
4. ALL existing test methods, not just the fixed ones
5. All fields and setup methods

ABSOLUTELY FORBIDDEN SHORTCUTS:
- DO NOT use placeholders like "// All existing test methods remain the same..."
- DO NOT use "// [Previous test methods continue unchanged...]"
- DO NOT use "// ... existing code ..."
- DO NOT use "// [Previous imports remain exactly the same]"
- DO NOT use ANY comments that indicate omitted code
- You MUST include ALL actual code verbatim, even if it's unchanged
- Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
- I need the complete verbatim code that can be directly saved to a file and compiled

STRICT ANTI-MOCKING REQUIREMENTS:
- ABSOLUTELY NO use of any mocking frameworks (Mockito, EasyMock, PowerMock, etc.)
- ABSOLUTELY NO @Mock, @MockBean, @InjectMocks, or any mock-related annotations
- ABSOLUTELY NO imports from org.mockito.* or static imports from Mockito
- ABSOLUTELY NO mock(), when(), verify(), or any mocking methods
- Use ONLY real objects and direct instantiation for testing
- Create real instances of dependencies instead of mocks

Format your entire response as a SINGLE complete Java file that I can save and run directly.
  
    CLASS INFO:
    - Class name: {class_name}
    - Package name: {package_name}


    Source code:
    ```java
    {source_code} 
    ```

    Test code:
    ```java
    {test_code}
    ```

    """

        # if there is an error message, add it to the prompt
        if error_message:
            prompt += f"""
    Error message:
    {error_message}

    These errors indicate compilation issues in the test code. Please fix these issues, especially:
    1. Ensure correct handling of class declarations and import statements
    2. Add missing class fields and initialization
    3. Solve potential structural problems, such as mismatched parentheses or formatting issues
    4. If you find variables that are used but not declared, please add appropriate declarations
    
    STRICT ANTI-MOCKING REQUIREMENTS FOR FIXING:
    - REMOVE any mocking framework imports (org.mockito.*, EasyMock, PowerMock)
    - REMOVE any @Mock, @MockBean, @InjectMocks annotations
    - REPLACE any mock(), when(), verify() calls with real object instantiation
    - Use ONLY real objects and direct instantiation for testing

    Please provide the complete fixed test code, ensuring it can be compiled correctly. Provide the full code, I need the completed code, not just the fixed part.
    """
        else:
            prompt += """
    Please check the following issues in the test code and fix them:
    1. Ensure the class declaration is correct and matches the class name and package name
    2. Check if all import statements are complete
    3. Verify that all variables used are properly declared or initialized
    4. Ensure the test method structure is correct, with each method having the @Test annotation
    5. Fix any syntax errors or mismatched parentheses
    
    STRICT ANTI-MOCKING REQUIREMENTS FOR FIXING:
    - REMOVE any mocking framework imports (org.mockito.*, EasyMock, PowerMock)
    - REMOVE any @Mock, @MockBean, @InjectMocks annotations
    - REPLACE any mock(), when(), verify() calls with real object instantiation
    - Use ONLY real objects and direct instantiation for testing

CRITICAL: I need the ENTIRE test class including ALL original methods, not just the fixed parts.
Your response must contain:
1. All package declarations
2. All import statements 
3. The complete class definition
4. ALL existing test methods, not just the fixed ones
5. All fields and setup methods

ABSOLUTELY FORBIDDEN SHORTCUTS:
- DO NOT use "// All existing test methods remain the same..."
- DO NOT use "// [Previous test methods continue unchanged...]"
- DO NOT use "// ... existing code ..."
- DO NOT use "// [Previous imports remain exactly the same]"
- DO NOT use ANY placeholders or comments indicating omitted code
- You MUST include ALL actual code verbatim, even if it's unchanged
- Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
- I need the complete verbatim code that can be directly saved to a file and compiled

Format your entire response as a SINGLE complete Java file that I can save and run directly.
    """

        # call the LLM API
        try:
            api_response = call_anthropic_api(prompt)
            # api_response = call_deepseek_api(prompt)
            
            if not api_response or len(api_response) < 100:  # ensure enough response
                logger.warning("LLM response is insufficient, trying alternative API")
                api_response = call_gpt_api(prompt)
                
            # extract the Java code
            fixed_code = extract_java_code(api_response)
            
            if not fixed_code or len(fixed_code) < 100:
                logger.warning("cannot extract valid Java code from LLM response")
                return test_code  # return the original code
                
            logger.info("LLM successfully fixed the test code")
            return fixed_code
            
        except Exception as e:
            logger.error(f"error calling LLM API: {str(e)}")
            return test_code  # return the original code


    def merge_bug_finding_methods_into_best_test(self, best_coverage_test, bug_finding_tests):
        try:
            best_test_code = best_coverage_test.get("test_code", self.initial_test_code)
            
            # Extract methods from best coverage test
            extractor = TestMethodExtractor()
            best_methods = extractor.extract_methods(best_test_code)
            best_method_names = {m.get("name"): m for m in best_methods if isinstance(m, dict)}
            
            # Track methods to add
            methods_to_add = []
            added_method_names = set()
            
            # Process verified bugs first (highest priority)
            if hasattr(self, 'verified_bug_methods') and self.verified_bug_methods:
                for method in self.verified_bug_methods:
                    if not isinstance(method, dict) or "code" not in method:
                        continue
                        
                    # Extract method name for deduplication
                    name_match = re.search(r'void\s+(\w+)\s*\(', method["code"])
                    if not name_match:
                        continue
                        
                    method_name = name_match.group(1)
                    
                    # Skip if this method or a similar one is already added
                    if method_name in added_method_names or method_name in best_method_names:
                        continue
                        
                    # Skip false positives
                    if method.get("verified", False) and not method.get("is_real_bug", True):
                        logger.info(f"Skipping verified false positive method: {method_name}")
                        continue
                    
                    # Add verified bug method
                    methods_to_add.append({
                        "name": method_name,
                        "code": method["code"],
                        "priority": 10  # High priority for verified bugs
                    })
                    added_method_names.add(method_name)
            
            # Then look at unverified but high-confidence bugs
            for test in bug_finding_tests:
                bugs = test.get("bugs", [])
                # Only consider tests with bugs that have high confidence
                high_confidence_bugs = [b for b in bugs if b.get("confidence", 0) > 0.7]
                if not high_confidence_bugs:
                    continue
                    
                # Extract bug methods from this test
                for bug in high_confidence_bugs:
                    method_name = bug.get("test_method")
                    if not method_name or method_name in added_method_names:
                        continue
                        
                    # Find the method code
                    method_code = None
                    methods = test.get("methods", [])
                    for m in methods:
                        if isinstance(m, str) and method_name in m:
                            method_code = m
                            break
                    
                    if method_code:
                        methods_to_add.append({
                            "name": method_name, 
                            "code": method_code,
                            "priority": 5  # Medium priority
                        })
                        added_method_names.add(method_name)
            
            # Sort methods by priority
            methods_to_add.sort(key=lambda x: x.get("priority", 0), reverse=True)
            
            # Combine best test with bug methods
            if methods_to_add:
                logger.info(f"Merging {len(methods_to_add)} verified/high-confidence bug methods into best coverage test")
                
                # Add methods one by one to avoid duplication
                result_code = best_test_code
                class_end = result_code.rfind("}")
                if class_end <= 0:
                    return best_test_code
                    
                merged_code = result_code[:class_end]
                for method in methods_to_add:
                    # Add comment indicating this is a verified bug method
                    method_with_comment = "\n    // Verified bug-finding method\n    " + method["code"].replace("\n", "\n    ")
                    merged_code += method_with_comment
                    
                merged_code += "\n}"
                return merged_code
                
            return best_test_code
            
        except Exception as e:
            logger.error(f"Error merging bug methods: {str(e)}")
            return best_coverage_test.get("test_code", self.initial_test_code)
    

    def fix_test_expectations(self, test_code):
        """
        Fix test expectations to match actual behavior
        
        Parameters:
        test_code (str): Test code to fix
        
        Returns:
        str: Fixed test code or original if couldn't fix
        """
        if not test_code:
            return test_code
        
        logger.info("Attempting to fix test expectations in final code...")
        
        try:
            # Extract methods from the test
            method_extractor = TestMethodExtractor()
            methods = method_extractor.extract_methods(test_code)
            
            if not methods:
                return test_code
            
            # Attempt to fix problematic methods
            fixed_methods = []
            fixed_count = 0
            
            for method in methods:
                if isinstance(method, dict) and "code" in method and "name" in method:
                    method_code = method["code"]
                    method_name = method["name"]
                    
                    # Identify known problematic tests
                    is_url_test = any(term in method_name.lower() for term in ["url", "createurl"])
                    is_bigdecimal_test = any(term in method_name.lower() for term in ["bigdecimal", "empty"])
                    is_large_number_test = any(term in method_name.lower() for term in ["large", "decimal"])
                    
                    if is_url_test or is_bigdecimal_test or is_large_number_test:
                        fixed_code = attempt_to_fix_test_expectations(method_code, "")
                        if fixed_code and fixed_code != method_code:
                            fixed_methods.append({"name": method_name, "code": fixed_code})
                            fixed_count += 1
                        else:
                            fixed_methods.append({"name": method_name, "code": method_code})
                    else:
                        fixed_methods.append({"name": method_name, "code": method_code})
            
            # If no methods were fixed, return original
            if fixed_count == 0:
                return test_code
            
            # Build a new test class with fixed methods
            result = self.rebuild_test_class(test_code, fixed_methods)
            if result:
                logger.info(f"Successfully fixed {fixed_count} test methods")
                return result
                
        except Exception as e:
            logger.error(f"Error while fixing test expectations: {str(e)}")
            logger.error(traceback.format_exc())
        
        return test_code

    def rebuild_test_class(self, original_test, methods):
        """
        Rebuild test class with the given methods
        
        Parameters:
        original_test (str): Original test class code
        methods (list): List of method dictionaries to include
        
        Returns:
        str: Rebuilt test class or None if failed
        """
        try:
            # Extract class definition and imports
            class_pattern = r'(package.*?class\s+\w+\s*\{)'
            class_match = re.search(class_pattern, original_test, re.DOTALL)
            
            if not class_match:
                return None
                
            class_def = class_match.group(1)
            
            # Extract fields and setUp method
            setup_and_fields = []
            field_pattern = r'(private\s+[\w<>[\]]+\s+\w+\s*;)'
            field_matches = re.finditer(field_pattern, original_test)
            for match in field_matches:
                setup_and_fields.append(match.group(1))
            
            setup_pattern = r'(@BeforeEach[\s\S]*?void\s+setUp\(\)[\s\S]*?\{[\s\S]*?\})'
            setup_match = re.search(setup_pattern, original_test)
            if setup_match:
                setup_and_fields.append(setup_match.group(1))
            
            # Build new test
            new_test = class_def + "\n\n"
            
            # Add fields and setup
            for item in setup_and_fields:
                new_test += "    " + item + "\n\n"
            
            # Add all methods
            for method in methods:
                new_test += "    " + method["code"].replace("\n", "\n    ") + "\n\n"
            
            # Close class
            new_test += "}"
            
            return new_test
            
        except Exception as e:
            logger.error(f"Error rebuilding test class: {str(e)}")
            return None

    def selection(self):
        """Select a promising node to expand"""
        node = self.root
        
        # If root not evaluated yet, return it
        if not node.state.executed:
            return node
            
        # If root has untried actions, return it
        if node.untried_actions is None:
            node.generate_possible_actions(self.test_prompt, self.source_code, self.uncovered_data)
            return node
            
        # Traverse the tree to find the best node to expand
        while node.is_fully_expanded() and node.children:
            node = node.best_child(self.exploration_weight)
            
        return node
        
    def expansion(self, node):
        """Expand node by trying an untried action"""
        # Select action based on priority
        if not node.untried_actions:
            return node
            
        # By default, use highest priority action, but occasionally try a different action
        # to increase diversity and exploration
        if random.random() < 0.85:  # 85% of the time take the highest priority action
            action = node.untried_actions.pop(0)
        else:
            # 15% of the time, randomly select an action (but weight by priority)
            if len(node.untried_actions) > 1:
                # Convert priorities to probabilities
                priorities = [a.get('priority', 50) for a in node.untried_actions]
                total_priority = sum(priorities)
                if total_priority > 0:
                    probabilities = [p/total_priority for p in priorities]
                    action_idx = random.choices(range(len(node.untried_actions)), weights=probabilities, k=1)[0]
                else:
                    action_idx = random.randint(0, len(node.untried_actions)-1)
                
                action = node.untried_actions.pop(action_idx)
            else:
                action = node.untried_actions.pop(0)
        
        # Create prompt based on the action
        prompt = self.create_action_prompt(node.state, action)
        
        # Check if we have a cached response for this prompt
        prompt_hash = hash(prompt)
        if prompt_hash in self.prompt_cache:
            logger.info(f"Using cached response for action: {action['description']}")
            improved_test_code = self.prompt_cache[prompt_hash]
        else:
            # Call LLM to get improved test code
            logger.info(f"Calling LLM for action: {action['description']}")
            if self.use_anthropic:
                api_response = call_anthropic_api(prompt)
                # api_response = call_deepseek_api(prompt)
            else:
                # api_response = call_gpt_api(prompt)
                api_response = call_deepseek_api(prompt)
                
            # Extract Java code
            improved_test_code = extract_java_code(api_response)
            
            # Cache valid responses
            if improved_test_code:
                self.prompt_cache[prompt_hash] = improved_test_code
        
        if not improved_test_code:
            logger.error("Failed to extract test code from LLM response")
            # Use the parent's test code as fallback
            improved_test_code = node.state.test_code
        
        # Check if the code is valid and apply fixes
        improved_test_code = self.validator.validate_and_fix(
            improved_test_code,
            [],  # No errors yet
            self.class_name,
            self.package_name,
            self.source_code
        )
        
        # Create new state with improved test code
        # Pass verify_bugs_mode to control immediate verification
        verify_during_expansion = self.verify_bugs_mode == "immediate"
        
        new_state = TestState(
            improved_test_code,
            self.class_name,
            self.package_name,
            self.project_dir,
            self.source_code
        )
        
        # Evaluate new state
        new_state.evaluate(self.validator, verify_during_expansion)
        
        # Add child node
        child = node.add_child(new_state, action)
        
        return child
        
    def simulation(self, node):
        """Calculate reward for the state with domain knowledge guidance"""
        if not node.state.executed:
            # Pass verify_bugs_mode to control immediate verification
            verify_during_sim = self.verify_bugs_mode == "immediate"
            node.state.evaluate(self.validator, verify_during_sim)
        
        # Get parent state for comparison
        parent_state = node.parent.state if node.parent else None
        
        # Calculate base reward
        reward = self.calculate_reward(node.state, parent_state)
        
        # Add domain-specific intelligence to guide simulation
        # 1. Analyze source code for specific patterns
        if self.source_code:
            # Check if source involves CSV parsing or formatting
            if "CSVParser" in self.source_code or "CSVFormat" in self.source_code:
                # Look for tests targeting specific parsing issues
                if node.action and "test_with_special_chars" in node.action.get("type", ""):
                    # Bonus for special character tests in CSV context
                    reward += 15.0
                
                if node.action and "test_empty_null_values" in node.action.get("type", ""):
                    # Bonus for empty/null tests in CSV context
                    reward += 20.0
                    
                if node.action and "test_with_numeric_edge_cases" in node.action.get("type", ""):
                    # Bonus for numeric edge cases in CSV context
                    reward += 12.0
            
            # Check if source involves archive/compression handling
            if "Archive" in self.source_code or "Compress" in self.source_code:
                # Look for tests targeting specific archive issues
                if node.action and "test_boundary_file_structures" in node.action.get("type", ""):
                    # Bonus for file structure tests in archive context
                    reward += 25.0
                
                if node.action and "test_permission_flag_combinations" in node.action.get("type", ""):
                    # Bonus for permission tests in archive context
                    reward += 22.0
                    
                if node.action and "test_sequential_operations" in node.action.get("type", ""):
                    # Bonus for sequential operation tests in archive context
                    reward += 18.0
        
        # 2. Examine which API methods are being tested
        methods_tested = []
        for method in node.state.test_methods:
            if isinstance(method, dict) and "code" in method:
                method_code = method["code"]
                # Extract method calls from the test
                method_calls = re.findall(r'(\w+)\s*\([^)]*\)', method_code)
                methods_tested.extend(method_calls)
        
        # 3. Give bonus for testing specific bug-prone API methods
        bug_prone_methods = ["parse", "format", "create", "convert", "get", "read", "write"]
        bug_prone_count = sum(1 for m in methods_tested if m in bug_prone_methods)
        if bug_prone_count > 0:
            reward += 5.0 * min(bug_prone_count, 5)  # Cap at 5 methods
        
        # 4. Bonus for edge case combinations that might reveal bugs
        edge_case_combinations = 0
        test_code = node.state.test_code.lower()
        
        # Look for specific combinations known to trigger bugs
        if "empty" in test_code and "header" in test_code:
            edge_case_combinations += 1
        if "path" in test_code and "/" in test_code:
            edge_case_combinations += 1
        if "permission" in test_code and "symlink" in test_code:
            edge_case_combinations += 1
        if "iterator" in test_code and "hasNext" in test_code and "next" in test_code:
            edge_case_combinations += 1
        if "quote" in test_code and ("unicode" in test_code or "utf" in test_code):
            edge_case_combinations += 1
        if "negative" in test_code and "number" in test_code:
            edge_case_combinations += 1
        
        reward += 10.0 * edge_case_combinations
        
        return reward



    def backpropagation(self, node, reward):
        """Update statistics for all nodes up to the root"""
        while node:
            node.update(reward)
            node = node.parent
            
    def calculate_reward(self, state, parent_state=None):
        """Calculate reward balancing coverage and bug finding with verification awareness"""
        # Base reward from coverage
        reward = state.coverage
        
        # 检查是否有特殊字符测试
        has_special_char_tests = any(
            re.search(r'[^\x00-\x7F]', m.get("code", "")) 
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # check whether there are negative number tests
        has_negative_num_tests = any(
            re.search(r'[-]\d+', m.get("code", "")) 
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # check whether there are multiple iterator tests
        has_multi_iterator_tests = any(
            ("Iterator" in m.get("code", "") and m.get("code", "").count("next()") > 1)
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # NEW: check whether there are null/empty tests
        has_empty_null_tests = any(
            ("null" in m.get("code", "").lower() or "empty" in m.get("code", "").lower() or 
            "\"\"" in m.get("code", "") or "''" in m.get("code", ""))
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # NEW: check whether there are special path character tests
        has_path_char_tests = any(
            (("/" in m.get("code", "") and "path" in m.get("code", "").lower()) or 
            "\\" in m.get("code", "") or 
            "File.separator" in m.get("code", ""))
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # NEW: check whether there are permission tests
        has_permission_tests = any(
            ("permission" in m.get("code", "").lower() or 
            "unix" in m.get("code", "").lower() or
            "chmod" in m.get("code", "").lower() or
            "0777" in m.get("code", ""))
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # NEW: check whether there are format compatibility tests
        has_format_compat_tests = any(
            ("format" in m.get("code", "").lower() and 
            ("excel" in m.get("code", "").lower() or 
            "csv" in m.get("code", "").lower() or
            "standard" in m.get("code", "").lower()))
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # NEW: check whether there are sequential operation tests
        has_sequential_tests = any(
            m.get("code", "").count(".") > 5  # 多个链式操作调用
            for m in state.test_methods if isinstance(m, dict)
        )
        
        # diversity reward
        if has_special_char_tests:
            reward += 15.0
        
        if has_negative_num_tests:
            reward += 10.0
        
        if has_multi_iterator_tests:
            reward += 20.0
            
        # NEW: extra diversity reward
        if has_empty_null_tests:
            reward += 17.0
            
        if has_path_char_tests:
            reward += 18.0
            
        if has_permission_tests:
            reward += 16.0
            
        if has_format_compat_tests:
            reward += 18.0
            
        if has_sequential_tests:
            reward += 15.0
        
        # if focusing on bugs, adjust the weight
        if hasattr(self, 'focus_on_bugs') and self.focus_on_bugs:
            # reward for finding bugs is doubled
            bug_multiplier = 2.0
        else:
            bug_multiplier = 1.0
        
        # Strongly penalize compilation errors
        error_count = len(state.compilation_errors)
        if error_count > 0:
            reward -= 15.0 * error_count
        
        # severely penalize nested classes
        if state.has_nested_classes:
            reward -= 50.0
        
        # reward for verified bugs (higher reward)
        verified_bugs = [bug for bug in state.detected_bugs 
                        if bug.get("verified", False) and bug.get("is_real_bug", True)]
        
        for bug in verified_bugs:
            severity_multiplier = 1.0
            if bug.get("severity") == "critical":
                severity_multiplier = 2.5
            elif bug.get("severity") == "high":
                severity_multiplier = 2.0
            
            # Higher reward for verified bugs
            reward += 40.0 * severity_multiplier * bug_multiplier
        
        # penalty for verified false positives
        false_positives = [bug for bug in state.detected_bugs 
                        if bug.get("verified", False) and not bug.get("is_real_bug", True)]
        reward -= 5.0 * len(false_positives)
        
        # handle assertion failures (potential bugs)
        assertion_count = 0
        if hasattr(state, 'assertion_failures'):
            assertion_count = len(state.assertion_failures)
        else:
            # Count assertion failures from detected bugs if assertion_failures not available
            assertion_count = len([bug for bug in state.detected_bugs if bug.get("type") == "assertion_failure"])
            
        if assertion_count > 0:
            # Check for long execution times (potential performance issues)
            if state.execution_time > 5.0:
                reward += 30.0 * bug_multiplier  # High reward for finding performance issues
            elif state.execution_time > 2.0:
                reward += 10.0 * bug_multiplier  # Medium reward
            elif assertion_count <= 3:
                # Small reward for few assertion failures
                reward += 5.0 * bug_multiplier
            else:
                # Penalize too many assertion failures
                reward -= 1.0 * (assertion_count - 3)
        
        # Reward for memory errors or very long execution times
        critical_bugs = [bug for bug in state.detected_bugs if
                        bug.get("type") == "memory_error" or
                        bug.get("severity") == "critical" or
                        (bug.get("type") == "long_execution" and 
                         (isinstance(state.execution_time, (int, float)) and state.execution_time > 8.0))]
        
        if critical_bugs:
            reward += 100.0 * bug_multiplier  # High reward for critical bugs
        elif isinstance(state.execution_time, (int, float)) and state.execution_time > 8.0:
            reward += 80.0 * bug_multiplier  # High reward for very long execution (potential infinite loop)
        elif isinstance(state.execution_time, (int, float)) and state.execution_time > 5.0:
            reward += 40.0 * bug_multiplier  # Medium reward for long execution (potential performance issue)
        
        # Reward for unverified bugs based on confidence
        unverified_bugs = [bug for bug in state.detected_bugs if not bug.get("verified", False)]
        for bug in unverified_bugs:
            # Unverified bugs get smaller bonus based on confidence
            severity_multiplier = 1.0
            if bug.get("severity") == "critical":
                severity_multiplier = 1.5
            elif bug.get("severity") == "high":
                severity_multiplier = 1.2
            
            confidence = bug.get("confidence", 0.5)
            reward += 15.0 * confidence * severity_multiplier * bug_multiplier
        
        # Compare with parent state if available
        if parent_state:
            # Reward for coverage improvement
            coverage_improvement = state.coverage - parent_state.coverage
            reward += 5.0 * coverage_improvement
            
            # Reward for fixing compilation errors
            error_improvement = len(parent_state.compilation_errors) - len(state.compilation_errors)
            if error_improvement > 0:
                reward += 3.0 * error_improvement
                
            # Reward for fixing nested class issues
            if parent_state.has_nested_classes and not state.has_nested_classes:
                reward += 50.0
                
            # Reward for finding new bugs
            new_bugs = len(state.detected_bugs) - len(parent_state.detected_bugs)
            if new_bugs > 0:
                reward += 20.0 * new_bugs * bug_multiplier
                
            # Reward for verified bugs (higher than regular bugs)
            new_verified_bugs = state.count_verified_bugs() - parent_state.count_verified_bugs()
            if new_verified_bugs > 0:
                reward += 40.0 * new_verified_bugs * bug_multiplier
            
            # Reward for execution time differences (finding potential issues)
            if (isinstance(state.execution_time, (int, float)) and 
                isinstance(parent_state.execution_time, (int, float)) and
                state.execution_time > parent_state.execution_time * 3 and state.execution_time > 5.0):
                reward += 50.0 * bug_multiplier  # High reward for finding potential performance issues
        
        return reward

    def create_action_prompt(self, state, action):
        """Create a specialized prompt based on the action"""
        # Prepare error summary
        error_summary = []
        if state.compilation_errors:
            error_summary.append(f"Compilation/Runtime Errors ({len(state.compilation_errors)}):")
            for err in state.compilation_errors[:3]:
                error_summary.append(f"- {err}")
            if len(state.compilation_errors) > 3:
                error_summary.append(f"- ... and {len(state.compilation_errors) - 3} more errors")
                
        if state.assertion_failures:
            error_summary.append(f"Assertion Failures (potential bugs discovered) ({len(state.assertion_failures)}):")
            for err in state.assertion_failures[:3]:
                error_summary.append(f"- {err}")
            if len(state.assertion_failures) > 3:
                error_summary.append(f"- ... and {len(state.assertion_failures) - 3} more assertion failures")
        
        # Include information about verified bugs if any
        verified_bugs = [bug for bug in state.detected_bugs 
                        if bug.get("verified", False) and bug.get("is_real_bug", True)]
        if verified_bugs:
            error_summary.append(f"Verified Bugs ({len(verified_bugs)}):")
            for bug in verified_bugs[:3]:
                error_summary.append(f"- {bug.get('type', 'Unknown')}: {bug.get('error', bug.get('description', 'No details'))}")
                if bug.get("verification_reasoning"):
                    error_summary.append(f"  Verification: {bug.get('verification_reasoning')[:100]}...")
            if len(verified_bugs) > 3:
                error_summary.append(f"- ... and {len(verified_bugs) - 3} more verified bugs")
                
        # Include information about unverified bugs
        unverified_bugs = [bug for bug in state.detected_bugs 
                        if not bug.get("verified", False) and bug.get("confidence", 0) >= 0.7]
        if unverified_bugs:
            error_summary.append(f"High-Confidence Potential Bugs ({len(unverified_bugs)}):")
            for bug in unverified_bugs[:3]:
                error_summary.append(f"- {bug.get('type', 'Unknown')}: {bug.get('error', bug.get('description', 'No details'))}")
            if len(unverified_bugs) > 3:
                error_summary.append(f"- ... and {len(unverified_bugs) - 3} more potential bugs")
                
        if state.execution_time > 3.0:
            error_summary.append(f"Execution Time: {state.execution_time:.2f} seconds (may indicate performance issues)")
        
        error_text = "\n".join(error_summary)
        
        # Base prompt template
        prompt = f"""
CRITICAL: I need the ENTIRE test class including ALL original methods, not just the fixed parts.
Your response must contain:
1. All package declarations
2. All import statements 
3. The complete class definition
4. ALL existing test methods, not just the fixed ones
5. All fields and setup methods
6. Don't use any mockito related code

ABSOLUTELY FORBIDDEN SHORTCUTS:
- DO NOT use "// All existing test methods remain the same..."
- DO NOT use "// [Previous test methods continue unchanged...]"
- DO NOT use "// ... existing code ..."
- DO NOT use "// [Previous imports remain exactly the same]"
- DO NOT use ANY placeholders or comments indicating omitted code
- You MUST include ALL actual code verbatim, even if it's unchanged
- Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
- I need the complete verbatim code that can be directly saved to a file and compiled

Format your entire response as a SINGLE complete Java file that I can save and run directly.
    ===============================
    JAVA CLASS UNIT TEST GENERATION WITH FEEDBACK
    ===============================

    -----------------
    SOURCE CODE
    -----------------
    ```java
    {state.source_code}
    ```

    -----------------
    CURRENT TEST CODE
    -----------------
    ```java
    {state.test_code}
    ```

    -----------------
    TEST STATUS
    -----------------
    Coverage: {state.coverage:.2f}%
    Uncovered lines: {len(state.uncovered_lines)}
    Uncovered branches: {len(state.uncovered_branches)}
    Potential bugs discovered: {len(state.detected_bugs)}
    Verified bugs: {len(verified_bugs)}
    Test execution time: {state.execution_time:.2f} seconds

    {error_text}

    -----------------
    IMPORTANT REQUIREMENTS
    -----------------
    1. DO NOT use @Nested annotations or nested test classes
    2. DO NOT use inner classes for test organization 
    3. Use a single flat test class structure
    4. Use clear method naming to organize related tests
    5. Each test method must be independent and at the top level of the test class
    6. Always include these imports:
    import org.junit.jupiter.api.Test;
    import org.junit.jupiter.api.BeforeEach;
    import static org.junit.jupiter.api.Assertions.*;
    import java.util.List;
    import java.util.ArrayList;
    import java.util.Arrays;
    import java.util.Iterator;
    import java.time.Duration;
    import org.junit.jupiter.api.DisplayName;
    """
        
        # Add action-specific instructions
        if action['type'] == 'target_line':
            line_number = action['line']
            line_context = self.get_source_line_context(line_number)
            
            prompt += f"""
    SPECIFIC INSTRUCTION:
    Your task is to add or modify tests to cover the following uncovered line, don't use any mockito related code:

    {line_context}

    Please analyze what inputs or conditions would cause this line to execute, and create a test case to reach it.
    Focus specifically on this uncovered line while maintaining overall test quality.
    """

        elif action['type'] == 'target_branch':
            line_number = action['line']
            line_context = self.get_source_line_context(line_number)
            
            prompt += f"""
    SPECIFIC INSTRUCTION:
    Your task is to add or modify tests to cover the branch condition at line {line_number}:

    {line_context}

    A branch condition (like an if statement) needs tests for both the true and false conditions.
    Please ensure there are tests that exercise both paths of this branch.
    """

        elif action['type'] == 'target_method':
            method = action['method']
            
            prompt += f"""
    SPECIFIC INSTRUCTION:
    Your task is to add or modify tests to cover the following method:

    {method}

    Create comprehensive tests for this method, considering different inputs and edge cases.
    """

        elif action['type'] == 'fix_critical_errors':
            prompt += f"""
    SPECIFIC INSTRUCTION:
    Fix the compilation and runtime errors in the current test suite. These errors are preventing proper execution.

    Look carefully at the error messages and fix each issue:
    {chr(10).join(f"- {err}" for err in state.compilation_errors[:10])}

    DO NOT use String.repeat() method as it's not available in the Java version used.
    DO NOT use any mockito related code
    DO NOT call private methods directly - check the modifier in the source code first.
    Make sure all necessary imports are included.
    Declare exceptions with 'throws' where necessary.
    """

        elif action['type'] == 'flatten_test_structure':
            prompt += """
    SPECIFIC INSTRUCTION:
    The current test contains nested classes or @Nested annotations which cause problems with coverage measurement.
    Your task is to refactor all tests to use a flat class structure:

    1. Move all test methods to the top level of the test class
    2. Replace nested class organization with clear method naming conventions
    3. Make sure all test methods are still accessible and functional
    4. DO NOT use inner classes or @Nested annotations for organization
    5. DO NOT use any mockito related code
    This is a critical task as nested classes prevent proper coverage measurement.
    """

        elif action['type'] == 'investigate_assertions':
            prompt += """
    SPECIFIC INSTRUCTION:
    The tests have assertion failures that may indicate actual bugs in the code under test.
    Your task is to investigate these failures carefully:

    1. For each assertion failure, analyze what the code actually does vs what the test expects
    2. Add detailed comments explaining your findings
    3. Strengthen the test to clearly demonstrate the bug if it's a real issue
    4. Fix incorrect assertions if the test expectation is wrong
    5. DO NOT use any mockito related code
    Finding real bugs is valuable! Don't just change assertions to make tests pass if they're revealing actual issues.
    """

        elif action['type'] == 'hunt_bugs':
            prompt += """
    SPECIFIC INSTRUCTION:
    Focus on finding potential bugs in the class under test. Add tests that:

    1. Test extreme boundary values and edge cases
    2. Verify class invariants are maintained
    3. Test with unexpected inputs or sequences of operations
    4. Verify proper exception handling
    5. Check for race conditions or thread safety issues if applicable
    6. Look for potential infinite loops by testing with unusual inputs
    7. Check for resource leaks or memory issues

    Don't just focus on coverage - design tests that would reveal potential defects.
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_edge_cases':
            prompt += """
    SPECIFIC INSTRUCTION:
    Create tests targeting edge cases and boundary conditions that might reveal bugs:

    1. Test with null inputs where applicable
    2. Test with empty collections, strings, or arrays
    3. Test with extremely large values or collections
    4. Test with minimum and maximum allowed values
    5. Test values just inside and outside of allowed ranges
    6. Test with unusual characters or inputs
    7. Test combinations of edge cases

    Edge case testing is effective at finding bugs not revealed by normal inputs.
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_for_resource_issues':
            prompt += """
    SPECIFIC INSTRUCTION:
    Create tests that might detect resource issues like infinite loops, memory leaks, or excessive resource consumption:

    1. Test methods with inputs that might cause infinite recursion or loops
    2. Test boundary conditions that might not be properly handled
    3. Test with extremely large inputs that might cause memory issues
    4. Test scenarios where resources might not be properly closed or released
    5. Look for methods with complex looping or conditional logic that might have edge cases

    Use appropriate assertions and safety mechanisms to detect these issues.
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_with_special_chars':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests using special character inputs including:
    1. CJK characters (Chinese, Japanese, Korean)
    2. Emoji and other Unicode characters
    3. ASCII control characters

    Focus on:
    - Testing how the CSV parser handles these characters
    - Comparing output with expected behavior of other systems (like Excel)
    - Testing character escaping, quoting, and encoding handling
    - Testing with mixed character types in the same column
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_with_numeric_edge_cases':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests for numeric edge cases:
    1. Negative numbers in different columns (especially first column)
    2. Very large numbers near Integer/Long/Double limits
    3. Numbers with many decimal places
    4. Special values: NaN, Infinity, -0
    5. Numbers in scientific notation

    Focus on:
    - How these numbers are quoted in output
    - How parsing handles these special numeric formats
    - Differences in behavior between columns
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_with_multiple_iterators':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests for multiple iterator scenarios:
    1. Creating multiple iterators from the same CSVParser
    2. Using peek operations (hasNext) followed by regular iterations
    3. Sharing lexers between multiple parser instances
    4. Mixing iteration styles (for-each, explicit next calls, etc.)

    Focus on:
    - Element consumption between iterators
    - State consistency when using multiple access patterns
    - Testing for unexpected interactions and lost elements
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_format_compatibility':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests that verify format compatibility with standard specifications:
    1. Compare output with Excel's expected behavior for identical inputs
    2. Test compliance with CSV RFC 4180 standard specifications
    3. Test special format handling differences with other systems
    4. Check handling of different line ending types (CR, LF, CRLF)

    Focus on:
    - Quoting rules and escaping behavior
    - Character encoding handling
    - Behavior with mixed formats in the same file
    - Compatibility with outputs from other systems
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_empty_null_values':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests for empty and null value handling:
    1. Empty column headers and empty values in different positions
    2. Null values vs empty strings vs spaces
    3. Series of empty columns at the beginning, middle, and end
    4. Handling of "null" string literal vs actual null values

    Focus on:
    - How empty headers are identified and distinguished
    - Whether empty values at different positions are treated consistently
    - Any special index handling or internal representation issues
    - Potential duplicates or conflicts with empty/null entries
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_boundary_file_structures':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests for file structure boundaries:
    1. Empty lines at start, middle, or end of files
    2. Files with only headers and no data
    3. Files with no headers but with data
    4. Files with trailing delimiters or special characters
    5. Handling of unexpected end-of-file scenarios

    Focus on:
    - How the parser handles unexpected file structure
    - Potential array index issues at boundaries
    - Memory allocation or buffer handling problems
    - Error handling for malformed structures
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_permission_flag_combinations':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests for permission flag and special value handling:
    1. Test extreme/boundary Unix permission values (0, 777, 177777)
    2. Check for symbolic link detection with different permission combinations
    3. Test special file types with unusual permission settings
    4. Test handling of invalid or malformed permission strings

    Focus on:
    - Edge cases in permission value ranges
    - Special bit flag combinations
    - How permission bits are interpreted for various file types
    - Potential integer overflow or underflow issues
    DO NOT use any mockito related code
    """

        elif action['type'] == 'test_sequential_operations':
            prompt += """
    SPECIFIC INSTRUCTION:
    Your task is to add tests that examine interaction between sequential operations:
    1. Create-then-modify-then-use patterns
    2. Multiple parser or formatter instances sharing data
    3. Reusing objects after certain operations
    4. Operation sequences that might leave objects in inconsistent states

    Focus on:
    - State preservation between operations
    - Object consistency after multiple operations
    - Resource handling during sequences
    - Order-dependent behaviors
    """
        elif action['type'] == 'test_malformed_html':
            prompt += """
            SPECIFIC INSTRUCTION:
            Add tests for malformed HTML handling:
            1. Test with unclosed tags: <div><span>text</div>
            2. Test with missing attribute quotes: <div class=myclass>
            3. Test with illegal nesting: <p><div>text</div></p>
            4. Test with HTML comments with missing closure
            
            Focus on:
            - How parser handles malformed HTML recovery
            - Exception handling or error recovery
            - Edge cases that might cause infinite loops
            """

        elif action['type'] == 'test_binary_data':
            prompt += """
            SPECIFIC INSTRUCTION:
            Create tests that parse non-HTML/binary data:
            1. Test parsing a binary file (use byte arrays)
            2. Test with incomplete UTF-8 sequences
            3. Test with extremely large input (to test memory handling)
            4. Test with intentionally malformed content
            
            Use a timeout to detect hanging parser:
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { 
                // Parser code that might hang
            });
            """

        elif action['type'] == 'verify_bugs' or action['type'] == 'verify_potential_bugs':
            # Get bug info - could be verified bugs or potential bugs
            bugs_info = "\n".join([
                f"- {bug.get('type', 'Unknown')}: {bug.get('error', bug.get('description', 'No details'))}" +
                (f" (Verification: {bug.get('verification_reasoning')[:100]}...)" 
                if bug.get('verified', False) and bug.get('verification_reasoning', '') else "")
                for bug in action.get('bugs', [])[:5]
            ])
            
            if len(action.get('bugs', [])) > 5:
                bugs_info += f"\n- ... and {len(action.get('bugs', [])) - 5} more issues"
            
            prompt += f"""
    SPECIFIC INSTRUCTION:
    I've identified {'verified' if action['type'] == 'verify_bugs' else 'potential'} bugs in the class. 
    Please add or modify tests to verify these issues:

    {bugs_info}

    Create targeted tests that can consistently reproduce these issues and clearly demonstrate the problem.
    Include detailed assertions and comments explaining what the expected behavior should be.
    """

        elif action['type'] == 'improve_assertions':
            prompt += """
    SPECIFIC INSTRUCTION:
    Focus on improving the quality of assertions in the tests. Look for:

    1. Tests with no assertions or weak assertions
    2. Opportunities to use more specific assertions
    3. Places where multiple aspects of the result should be verified
    4. Complex objects that should have their state thoroughly verified

    Make assertions precise, meaningful, and comprehensive.
    """

        elif action['type'] == 'refactor_tests':
            prompt += """
    SPECIFIC INSTRUCTION:
    Refactor the test suite to improve its organization and maintainability while preserving functionality:

    1. Extract common setup code into @BeforeEach methods
    2. Create helper methods for common operations
    3. Group related tests with descriptive names
    4. Add clear comments explaining complex tests
    5. Remove redundant or unnecessary code

    Ensure that your refactoring does not reduce test coverage or effectiveness.
    """

        # Add examples of successful tests if we have them in the method library
        if self.method_library:
            prompt += "\n\nEXAMPLES OF EFFECTIVE TEST METHODS:\n"
            examples_added = 0
            
            # First add verified bug methods if available
            for method in self.verified_bug_methods[:2]:
                if examples_added >= 2:
                    break
                    
                if isinstance(method, dict) and "code" in method:
                    prompt += f"\n```java\n{method['code']}\n```\n"
                    examples_added += 1
            
            # Then add from method library 
            if examples_added < 2:
                for key, method_code in self.method_library.items():
                    if examples_added >= 2:
                        break
                        
                    if 'bug' in key or 'critical' in key:
                        prompt += f"\n```java\n{method_code}\n```\n"
                        examples_added += 1
        
        # Final instructions
        prompt += """

    FINAL INSTRUCTIONS:
    1. Provide the complete test class code, with all necessary imports and annotations
    2. Use a FLAT CLASS STRUCTURE - no nested classes or @Nested annotations
    3. Group tests using clear method naming conventions instead of nested classes 
    4. Make each test method independent and directly within the main test class
    5. DO NOT use inner classes for organization - this will break coverage measurement
    6. Focus on addressing the specific instruction while maintaining overall test quality
    7. Be particularly vigilant for bugs that might cause infinite loops, memory issues, or resource leaks
    8. Don't include explanations outside the Java code
    CRITICAL: I need the ENTIRE test class including ALL original methods, not just the fixed parts.
Your response must contain:
1. All package declarations
2. All import statements 
3. The complete class definition
4. ALL existing test methods, not just the fixed ones
5. All fields and setup methods

ABSOLUTELY FORBIDDEN SHORTCUTS:
- DO NOT use "// All existing test methods remain the same..."
- DO NOT use "// [Previous test methods continue unchanged...]"
- DO NOT use "// ... existing code ..."
- DO NOT use "// [Previous imports remain exactly the same]"
- DO NOT use ANY placeholders or comments indicating omitted code
- You MUST include ALL actual code verbatim, even if it's unchanged
- Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
- I need the complete verbatim code that can be directly saved to a file and compiled

Format your entire response as a SINGLE complete Java file that I can save and run directly.
    """

        return prompt
    def get_source_line_context(self, line_number, context_lines=3):
        """Get a specific line from the source code with some context"""
        if not self.source_code:
            return f"Line {line_number} (context not available)"
            
        lines = self.source_code.split('\n')
        if not (1 <= line_number <= len(lines)):
            return f"Line {line_number} (out of range)"
            
        start = max(0, line_number - context_lines - 1)
        end = min(len(lines), line_number + context_lines)
        
        context = []
        for i in range(start, end):
            prefix = "→ " if i == line_number - 1 else "  "
            context.append(f"{prefix}{i+1}: {lines[i]}")
            
        return "\n".join(context)


def handle_false_positive_tests(test_code, verified_methods, all_bug_finding_methods):
    """处理被验证为误报的测试方法"""
    # 获取被验证为误报的方法
    false_positive_methods = [m for m in verified_methods 
                             if isinstance(m, dict) and 
                             m.get("verified", False) and not m.get("is_real_bug", True)]
    
    if not false_positive_methods:
        return test_code
        
    logger.info(f"Handling {len(false_positive_methods)} verified false positive test methods")
    
    modified_code = test_code
    
    for method in false_positive_methods:
        method_code = method.get("code", "")
        if not method_code:
            continue
            
        # 提取方法名以定位测试中的方法
        name_match = re.search(r'void\s+(\w+)\s*\(', method_code)
        if not name_match:
            continue
            
        method_name = name_match.group(1)
        
        # 选项1: 在测试方法上添加@Disabled注解
        method_pattern = r'(\s*)(@Test[^\n]*\s+(?:public\s+)?void\s+' + re.escape(method_name) + r'\s*\([^\)]*\))'
        
        # 添加@Disabled注解和解释性注释
        replacement = r'\1@Disabled("Verified as false positive - expected behavior differs from test expectations")\n\1\2'
        modified_code = re.sub(method_pattern, replacement, modified_code)
        
        logger.info(f"Disabled method {method_name} as it was verified to be a false positive")
    
    # 确保导入@Disabled
    if '@Disabled' in modified_code and 'import org.junit.jupiter.api.Disabled;' not in modified_code:
        # 在第一个import后添加Disabled导入
        import_pattern = r'(import\s+[^;]+;)'
        modified_code = re.sub(import_pattern, r'\1\nimport org.junit.jupiter.api.Disabled;', modified_code, count=1)
    
    return modified_code

def improve_test_coverage_with_enhanced_mcts(
    project_dir, prompt_dir, test_prompt_file, class_name, package_name, 
    initial_test_code, source_code, max_iterations=20, target_coverage=101.0, 
    verify_bugs_mode="batch", expose_bugs=True):
    """
    Use a single Enhanced MCTS tree to improve test coverage for a class
    
    Parameters:
    project_dir (str): Project directory
    prompt_dir (str): Prompt directory 
    test_prompt_file (str): Test prompt file path
    class_name (str): Class name
    package_name (str): Package name
    initial_test_code (str): Initial test code
    source_code (str): Source code
    max_iterations (int): Maximum iterations for the MCTS tree
    target_coverage (float): Target coverage percentage
    verify_bugs_mode (str): Bug verification strategy (immediate/batch/none)
    expose_bugs (bool): If True, prioritize exposing bugs in final test
    
    Returns:
    tuple: (best_test_code, best_coverage, has_critical_errors)
    """
    logger.info(f"Starting Enhanced MCTS-guided test improvement for {package_name}.{class_name}")
    
    # Create validator
    validator = TestValidator()
    
    # Read test prompt content
    with open(test_prompt_file, 'r', encoding='utf-8') as f:
        test_prompt = f.read()
    
    # Remove comments from the source code to reduce tokens
    # cleaned_source_code = strip_java_comments(source_code)
    cleaned_source_code = source_code
    
    # Run initial test to get baseline coverage
    test_file_path = save_test_code(initial_test_code, class_name, package_name, project_dir)
    if test_file_path:
        coverage_data, assertion_failures, execution_time, compilation_errors = run_tests_with_jacoco(
            project_dir, class_name, package_name, f"{package_name}.{class_name}Test", False, getattr(self, 'project_type', 'maven')
        )
        initial_coverage = get_coverage_percentage(coverage_data) if coverage_data else 0.0
        logger.info(f"Initial test coverage: {initial_coverage:.2f}%")
    else:
        logger.error("Failed to save initial test code")
        initial_coverage = 0.0

    
    # Create single MCTS generator with initial test as starting point
    # Use higher max_iterations since we're only doing one run
    mcts = EnhancedMCTSTestGenerator(
        project_dir=project_dir,
        prompt_dir=prompt_dir,
        class_name=class_name,
        package_name=package_name,
        initial_test_code=initial_test_code,
        source_code=cleaned_source_code,
        test_prompt=test_prompt,
        max_iterations=max_iterations,  # Higher number of iterations for single tree
        exploration_weight=1.0,  # Start with balanced exploration
        use_anthropic=True,  # Use Claude for higher quality
        verify_bugs_mode=verify_bugs_mode,  # Pass verification mode
        focus_on_bugs=expose_bugs,  # Use expose_bugs parameter to control focus
        initial_coverage=initial_coverage  # Pass initial coverage
    )
    
    # Run MCTS search with single tree
    improved_test, coverage = mcts.run_search()
    
    # Add defensive check - if returned None, use previous best test code
    if improved_test is None:
        logger.warning(f"MCTS returned None test code, falling back to initial test")
        improved_test = initial_test_code
    
    # Save and verify the final improved test
    final_file_path = save_test_code(improved_test, class_name, package_name, project_dir)
    if not final_file_path:
        logger.error("Failed to save final test code")
        return initial_test_code, initial_coverage, True
        
    # Run tests to get final metrics
    coverage_data, assertion_failures, execution_time, compilation_errors = run_tests_with_jacoco(
        project_dir, 
        class_name, 
        package_name,
        f"{package_name}.{class_name}Test",
        False,
        getattr(self, 'project_type', 'maven')
    )
    
    has_critical_errors = len(compilation_errors) > 0
    final_coverage = get_coverage_percentage(coverage_data) if coverage_data else 0.0
    
    logger.info(f"Final test result: Coverage={final_coverage:.2f}%, "
               f"Compilation Errors={len(compilation_errors)}, "
               f"Assertion Failures={len(assertion_failures)}")
    
    # Generate comprehensive summary with history
    status = "Success" if not has_critical_errors and final_coverage >= target_coverage else "Partial Success"
    
    # Pass the MCTS history to the summary generator
    generate_test_summary(project_dir, class_name, package_name, 
                         final_coverage, has_critical_errors, 
                         max_iterations, status, mcts.history)
    
    return improved_test, final_coverage, has_critical_errors

class TestValidator:
    """
    Utility class for validating and fixing test code
    """
    
    def __init__(self):
        # Common imports needed for JUnit tests
        self.standard_imports = [
            "import org.junit.jupiter.api.Test;",
            "import org.junit.jupiter.api.BeforeEach;",
            "import org.junit.jupiter.api.AfterEach;",
            "import org.junit.jupiter.api.DisplayName;",
            "import static org.junit.jupiter.api.Assertions.*;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "import java.util.Arrays;",
            "import java.util.Properties;",
            "import java.util.Iterator;",
            "import java.util.ListIterator;",
            "import java.time.Duration;",
            "import java.nio.charset.StandardCharsets;",
        ]
        
        # Patterns for common issues
        self.issue_patterns = {
            "method_not_found": re.compile(r"cannot find symbol.*method\s+(\w+)"),
            "class_not_found": re.compile(r"cannot find symbol.*class\s+(\w+)"),
            "var_not_found": re.compile(r"cannot find symbol.*variable\s+(\w+)"),
            "private_access": re.compile(r"(\w+).*has private access"),
            "unreported_exception": re.compile(r"unreported exception\s+([\w.]+)"),
            "incompatible_types": re.compile(r"incompatible types:\s+(.*)\s+cannot be converted to\s+(.*)"),
            "repeat_method": re.compile(r"cannot find symbol.*method repeat\(int\)"),
            "missing_bracket": re.compile(r"(.*expected.*})|(.*expected.*)"),
        }
        
        # Cache of previously successful fixes
        self.fix_cache = {}
        
    def validate_and_fix(self, test_code, error_messages, class_name, package_name, source_code=None):
        """
        Validate test code and fix common issues
        
        Parameters:
        test_code (str): Test code to validate and fix
        error_messages (list): List of error messages from previous compilation
        class_name (str): Name of the class being tested
        package_name (str): Package name
        source_code (str): Source code of the class being tested
        
        Returns:
        str: Fixed test code
        """
        if not test_code or not class_name:
            return test_code
            
        # Apply cached fixes for this class if available
        cache_key = f"{package_name}.{class_name}"
        if cache_key in self.fix_cache:
            for issue, fix in self.fix_cache[cache_key].items():
                test_code = self.apply_fix(test_code, issue, fix)
        
        # Fix package and imports
        test_code = self.fix_package_and_imports(test_code, package_name)
        
        # Fix specific issues based on error messages
        if error_messages:
            test_code = self.fix_from_errors(test_code, error_messages, class_name, source_code)
        
        # Fix common test structure issues
        test_code = self.fix_test_structure(test_code, class_name)
        
        # Fix access modifier issues
        if source_code:
            test_code = self.fix_access_modifiers(test_code, class_name, source_code)
        
        return test_code
        
    def apply_fix(self, test_code, issue, fix):
        """Apply a specific fix to the test code"""
        if issue == "add_imports":
            for import_stmt in fix:
                if import_stmt not in test_code:
                    # Find where to add the import - after package or after existing imports
                    if "package " in test_code:
                        package_end = test_code.find(';', test_code.find("package ")) + 1
                        if "import " in test_code[:package_end + 100]:
                            # Add after last import
                            last_import = test_code.rfind(';', 0, package_end + 200)
                            test_code = test_code[:last_import+1] + "\n" + import_stmt + test_code[last_import+1:]
                        else:
                            # Add after package
                            test_code = test_code[:package_end] + "\n\n" + import_stmt + test_code[package_end:]
                    else:
                        # Add at the beginning
                        test_code = import_stmt + "\n" + test_code
        elif issue == "replace_pattern":
            pattern, replacement = fix
            test_code = re.sub(pattern, replacement, test_code)
        
        return test_code
    
    def fix_package_and_imports(self, test_code, package_name):
        """Fix package declaration and ensure necessary imports"""
        # Check and fix package declaration
        if "package " not in test_code and package_name:
            test_code = f"package {package_name};\n\n{test_code}"
        
        # Add standard imports if not present
        for import_stmt in self.standard_imports:
            if import_stmt not in test_code:
                # Find where to add imports - after package or at the beginning
                if "package " in test_code:
                    package_end = test_code.find(';', test_code.find("package ")) + 1
                    if "import " in test_code[:package_end + 100]:
                        # Don't add, as there are already imports and we might duplicate
                        pass
                    else:
                        # Add after package
                        test_code = test_code[:package_end] + "\n\n" + import_stmt + test_code[package_end:]
                else:
                    # Add at the beginning
                    test_code = import_stmt + "\n" + test_code
        
        return test_code
    
    def fix_from_errors(self, test_code, error_messages, class_name, source_code):
        """Fix issues based on compilation error messages"""
        for error in error_messages:
            # Fix missing symbols (methods, classes, variables)
            for pattern_name, pattern in self.issue_patterns.items():
                match = pattern.search(error)
                if match:
                    if pattern_name == "method_not_found":
                        method_name = match.group(1)
                        # Add missing method import or fix method call
                        if method_name == "assertTimeout":
                            test_code = self.apply_fix(test_code, "add_imports", 
                                             ["import static org.junit.jupiter.api.Assertions.assertTimeout;"])
                    elif pattern_name == "class_not_found":
                        class_name = match.group(1)
                        # Add missing class import
                        if class_name == "Duration":
                            test_code = self.apply_fix(test_code, "add_imports", 
                                             ["import java.time.Duration;"])
                        elif class_name == "IOException":
                            test_code = self.apply_fix(test_code, "add_imports", 
                                             ["import java.io.IOException;"])
                    elif pattern_name == "unreported_exception":
                        exception_type = match.group(1)
                        # Add throws declaration or try-catch
                        test_code = self.apply_fix(test_code, "replace_pattern", 
                                         [r"(public void \w+\([^)]*\))( \{)", 
                                          r"\1 throws " + exception_type + r"\2"])
        
        return test_code
    
    def fix_test_structure(self, test_code, class_name):
        """Fix common test structure issues"""
        # Make sure the test class name is correct
        test_class_pattern = r"public\s+class\s+(\w+)"
        test_class_match = re.search(test_class_pattern, test_code)
        
        if test_class_match:
            test_class_name = test_class_match.group(1)
            expected_name = f"{class_name}Test"
            
            if test_class_name != expected_name:
                test_code = re.sub(
                    r"public\s+class\s+" + test_class_name, 
                    f"public class {expected_name}", 
                    test_code
                )
        
        return test_code
    
    def fix_access_modifiers(self, test_code, class_name, source_code):
        """Fix access modifier issues by using reflection when needed"""
        private_access_pattern = r"(\w+).*has private access"
        
        for line in test_code.split('\n'):
            if "private access" in line:
                match = re.search(private_access_pattern, line)
                if match:
                    private_member = match.group(1)
                    # Add reflection code to access private member
                    reflection_import = "import java.lang.reflect.*;"
                    if reflection_import not in test_code:
                        test_code = self.apply_fix(test_code, "add_imports", [reflection_import])
                    
                    # Replace direct access with reflection (simplified)
                    test_code = self.apply_fix(test_code, "replace_pattern",
                                    [f"\\b{class_name}\\.{private_member}\\b",
                                     f"getPrivateField({class_name}.class, \"{private_member}\")"])
                    
                    # Add helper method if not already present
                    if "getPrivateField" not in test_code:
                        helper_method = """
    // Helper method to access private fields using reflection
    private static Object getPrivateField(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }
"""
                        # Add before last closing bracket
                        test_code = test_code.rstrip() + "\n" + helper_method + "\n}"
                        # Remove duplicate closing bracket
                        test_code = test_code.replace("\n}\n}", "\n}")
        
        return test_code

class TestState:
    """
    Base class representing a test state
    """
    
    def __init__(self, test_code, class_name, package_name, project_dir, source_code=None, project_type='maven'):
        """
        Initialize test state
        
        Parameters:
        test_code (str): Test code
        class_name (str): Class name
        package_name (str): Package name
        project_dir (str): Project directory
        source_code (str): Source code (optional)
        project_type (str): Project type ('maven' or 'gradle')
        """
        self.test_code = test_code
        self.class_name = class_name
        self.package_name = package_name
        self.project_dir = project_dir
        self.source_code = source_code
        self.project_type = project_type
        
        # Test execution metrics
        self.executed = False
        self.coverage = 0.0
        self.compilation_errors = []
        self.detected_bugs = []
        self.has_bugs = False
        self.logical_bugs = []
        self.execution_time = 0.0
        
        # Test structure
        self.test_methods = []
        
        # Coverage data
        self.uncovered_lines = []
        self.uncovered_branches = []
        self.memory_errors = []
        self.assertion_failures = []
        
        # Issues and flags
        self.has_nested_classes = False
        
        # Extract methods on initialization
        self.extractor = TestMethodExtractor()
        
        # Parse test code to extract methods
        self.test_methods = self.extractor.extract_methods(test_code)
    
    def evaluate(self, validator=None, verify_bugs=False, current_iteration=None):
        """
        Run tests and measure coverage
        
        Parameters:
        validator (TestValidator): Optional validator for fixing test code
        verify_bugs (bool): Whether to verify bugs immediately
        current_iteration (int): Current iteration (optional)
        """
        # Save test code to file
        test_file = save_test_code(
            self.test_code, 
            self.class_name, 
            self.package_name, 
            self.project_dir
        )
        
        # Mark as executed
        self.executed = True
        
        # Run tests and measure coverage
        coverage_data, results, execution_time, errors = run_tests_with_jacoco(
            self.project_dir, 
            f"{self.package_name}.{self.class_name}Test",
            self.package_name,
            False,
            getattr(self, 'project_type', 'maven')
        )
        
        # Update metrics
        self.execution_time = execution_time
        
        # Get coverage percentage
        if coverage_data:
            self.coverage = get_coverage_percentage(coverage_data)
        
        # Check for compilation errors
        if errors:
            self.compilation_errors = errors
            
            # Try to fix compilation errors if validator provided
            if validator:
                fixed_code = validator.validate_and_fix(
                    self.test_code,
                    errors,
                    self.class_name,
                    self.package_name,
                    self.source_code
                )
                
                if fixed_code != self.test_code:
                    # Update test code with fixed version
                    self.test_code = fixed_code
                    
                    # Re-run evaluation with fixed code
                    self.evaluate(validator, verify_bugs, current_iteration)
            
            return
        
        # Check for test failures (potential bugs)
        if results:
            for result in results:
                if "failed" in result.lower() or "error" in result.lower():
                    # Extract bug information
                    bug_info = self.extract_bug_info(result)
                    if bug_info:
                        self.detected_bugs.append(bug_info)
                        
                        # Check if it's a logical bug
                        if self.is_logical_bug(bug_info):
                            self.has_bugs = True
                            self.logical_bugs.append(bug_info)
        
        # Re-extract methods after potential fixes
        self.test_methods = self.extractor.extract_methods(self.test_code)
    
    def extract_bug_info(self, result):
        """
        Extract bug information from test result
        
        Parameters:
        result (str): Test result
        
        Returns:
        dict: Bug information or None
        """
        bug_info = {
            "type": "unknown",
            "description": result,
            "method": "",
            "error": "",
            "severity": "medium",
            "verified": False,
            "is_real_bug": False
        }
        
        # Extract method name
        method_match = re.search(r'Test\.(test\w+)', result)
        if method_match:
            bug_info["method"] = method_match.group(1)
            
            # Extract error type
            error_match = re.search(r'(Assert\w+Error|AssertionError|Exception):', result)
            if error_match:
                error_type = error_match.group(1)
                bug_info["error"] = error_type
                
                if "AssertionError" in error_type:
                    bug_info["type"] = "assertion_failure"
                elif "Exception" in error_type:
                    bug_info["type"] = "exception"
                    
                # Determine if it's a logical bug
                if "expected" in result and "but was" in result:
                    # Parse expected vs actual
                    expected_match = re.search(r'expected:<(.+?)> but was:<(.+?)>', result)
                    if expected_match:
                        expected = expected_match.group(1)
                        actual = expected_match.group(2)
                        
                        # Extract test method
                        for method in self.test_methods:
                            if method.get("name") == bug_info["method"]:
                                # Try to determine if it's a logical bug
                                method_code = method.get("code", "")
                                if self.is_method_testing_logic(method_code):
                                    bug_info["bug_type"] = self.determine_bug_type(method_code, expected, actual)
                                    return bug_info
        
        return bug_info
    
    def is_logical_bug(self, bug_info):
        """
        Check if a bug is likely a logical bug
        
        Parameters:
        bug_info (dict): Bug information
        
        Returns:
        bool: True if likely a logical bug
        """
        # Check if we've already determined it's a logical bug
        if "bug_type" in bug_info:
            return True
            
        # Assertion failures are often logical bugs
        if bug_info["type"] == "assertion_failure" and "expected" in bug_info["description"]:
            # Extract the method being tested
            method_name = bug_info["method"]
            
            # Check if the method is testing logic
            for method in self.test_methods:
                if method.get("name") == method_name:
                    return self.is_method_testing_logic(method.get("code", ""))
        
        return False
    
    def is_method_testing_logic(self, method_code):
        """
        Check if a method is testing logical behavior
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        bool: True if testing logic
        """
        # Check for boolean logic assertions
        if "assertTrue" in method_code or "assertFalse" in method_code:
            return True
            
        # Check for comparison assertions
        if "assertEquals" in method_code and not "Exception" in method_code:
            return True
            
        # Check for specific logical patterns
        failures = [
            r'if\s*\(.+?\)', r'while\s*\(.+?\)', r'for\s*\(.+?\)',
            r'&&', r'\|\|', r'==', r'!=', r'<=', r'>='
        ]
        
        for pattern in failures:
            if re.search(pattern, method_code):
                return True
                
        return False
    
    def determine_bug_type(self, method_code, expected, actual):
        """
        Determine type of logic bug
        
        Parameters:
        method_code (str): Method code
        expected (str): Expected value
        actual (str): Actual value
        
        Returns:
        str: Logic bug type
        """
        # Check for off-by-one error
        try:
            expected_num = int(expected)
            actual_num = int(actual)
            if abs(expected_num - actual_num) == 1:
                return "off_by_one"
        except (ValueError, TypeError):
            pass
            
        # Check for boundary condition error
        if "boundary" in method_code.lower() or "edge" in method_code.lower():
            return "boundary_error"
        
        # Check for boolean logic error
        if "true" in str(expected).lower() and "false" in str(actual).lower() or \
           "false" in str(expected).lower() and "true" in str(actual).lower():
            return "boolean_bug"
            
        # Check for null handling error
        if "null" in str(expected).lower() or "null" in str(actual).lower():
            return "null_handling"
            
        # Default to general logical error
        return "failure_error"
    
    def count_logical_bugs(self):
        """Count number of logical bugs"""
        return len(self.logical_bugs)
    
    def count_verified_bugs(self):
        """Count number of verified bugs"""
        return sum(1 for bug in self.detected_bugs if bug.get("verified", False) and bug.get("is_real_bug", False))
    
    def get_bug_finding_methods(self):
        """Get methods that find bugs"""
        bug_methods = []
        
        for bug in self.detected_bugs:
            method_name = bug.get("method", "")
            
            # Find the method code
            for method in self.test_methods:
                if method.get("name") == method_name:
                    bug_finding_method = {
                        "method_name": method_name,
                        "code": method.get("code", ""),
                        "bug_type": bug.get("type", "unknown"),
                        "error": bug.get("error", ""),
                        "verified": bug.get("verified", False),
                        "is_real_bug": bug.get("is_real_bug", False)
                    }
                    
                    # Add logical bug information if available
                    if "bug_type" in bug:
                        bug_finding_method["bug_type"] = bug["bug_type"]
                        bug_finding_method["bug_category"] = "logical"
                    
                    bug_methods.append(bug_finding_method)
                    break
        
        return bug_methods
    
    def get_logical_bug_finding_methods(self):
        """Get methods that find logical bugs"""
        return [m for m in self.get_bug_finding_methods() 
                if "bug_category" in m and m["bug_category"] == "logical"]
    
    def get_bug_finding_test_methods(self):
        """Get test methods that find bugs"""
        return [m for m in self.test_methods 
                if m.get("name") in [bug.get("method") for bug in self.detected_bugs]]
    
    def extract_test_method_by_name(self, method_name):
        """
        Extract test method by name
        
        Parameters:
        method_name (str): Method name
        
        Returns:
        str: Method code or None
        """
        for method in self.test_methods:
            if method.get("name") == method_name:
                return method.get("code", "")
        return None
