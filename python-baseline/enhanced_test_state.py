#!/usr/bin/env python3
"""
Enhanced Test State

This module extends the base TestState with enhanced capabilities for more
detailed analysis and tracking of test execution results.
"""

import os
import re
import logging
import traceback
from collections import defaultdict

# Import from base implementation
from enhanced_mcts_test_generator import TestState

logger = logging.getLogger("enhanced_test_state")

class EnhancedTestState(TestState):
    """
    Enhanced version of TestState with more detailed analysis and tracking
    """
    
    def __init__(self, test_code, class_name, package_name, project_dir, source_code=None, project_type='maven'):
        """
        Initialize enhanced test state
        
        Parameters:
        test_code (str): Test code
        class_name (str): Class name
        package_name (str): Package name
        project_dir (str): Project directory
        source_code (str): Source code (optional)
        project_type (str): Project type ('maven' or 'gradle')
        """
        # Call parent constructor
        super().__init__(test_code, class_name, package_name, project_dir, source_code, project_type)
        
        # Enhanced tracking properties
        self.method_coverage = {}  # Coverage per test method
        self.branch_coverage = 0.0  # Branch coverage percentage
        self.line_coverage = 0.0   # Line coverage percentage
        self.method_execution_time = {}  # Execution time per test method
        
        # Additional bug information
        self.bug_categories = defaultdict(int)  # Count bugs by category
        self.bug_severity_counts = defaultdict(int)  # Count bugs by severity
        
        # Track verified bugs
        self.verified_bugs = []
        
        # Track test quality metrics
        self.avg_assertions_per_method = 0.0
        self.test_method_complexity = {}
        self.test_diversity_score = 0.0
        
        # Track assertions
        self.assertions = []
        
        # Track mutation score
        self.mutation_score = 0.0
        
        # Expanded bug categories for richer tracking
        self.bug_types = {
            # Logical bugs
            "logical": {
                "count": 0,
                "subtypes": defaultdict(int),
                "verified_count": 0
            },
            # Resource management bugs
            "resource_management": {
                "count": 0,
                "subtypes": defaultdict(int),
                "verified_count": 0
            },
            # Data operation bugs
            "data_operation": {
                "count": 0,
                "subtypes": defaultdict(int),
                "verified_count": 0
            },
            # Exception handling bugs
            "exception_handling": {
                "count": 0,
                "subtypes": defaultdict(int),
                "verified_count": 0
            },
            # Concurrency bugs
            "concurrency": {
                "count": 0,
                "subtypes": defaultdict(int),
                "verified_count": 0
            },
            # Input validation bugs
            "validation": {
                "count": 0,
                "subtypes": defaultdict(int),
                "verified_count": 0
            },
            # Security vulnerabilities
            "security": {
                "count": 0,
                "subtypes": defaultdict(int),
                "verified_count": 0
            }
        }
        
        # Extract assertions from test methods
        self._extract_assertions()
    
    def _extract_assertions(self):
        """Extract assertions from test methods"""
        assertion_pattern = r'assert\w+\s*\([^;]+\);'
        self.assertions = []
        total_assertions = 0
        
        for method in self.test_methods:
            if isinstance(method, dict) and "code" in method:
                # Find all assertions in this method
                method_assertions = re.findall(assertion_pattern, method["code"])
                
                # Add to total count
                total_assertions += len(method_assertions)
                
                # Add to assertions list
                for assertion in method_assertions:
                    self.assertions.append({
                        "method": method.get("name", "unknown"),
                        "assertion": assertion.strip()
                    })
        
        # Calculate average assertions per method
        if self.test_methods:
            self.avg_assertions_per_method = total_assertions / len(self.test_methods)
    
    def calculate_test_diversity_score(self):
        """
        Calculate a score representing test diversity
        
        Returns:
        float: Diversity score (0.0 to 1.0)
        """
        if not self.test_methods:
            return 0.0
            
        # Factors that contribute to diversity
        assertion_types = set()
        input_diversity = 0.0
        method_coverage_diversity = 0.0
        
        # Count unique assertion types
        for assertion in self.assertions:
            assert_type = re.match(r'assert(\w+)', assertion["assertion"])
            if assert_type:
                assertion_types.add(assert_type.group(1))
        
        # Check input diversity through string literals and numbers
        string_literals = set()
        number_literals = set()
        
        for method in self.test_methods:
            if isinstance(method, dict) and "code" in method:
                # Extract string literals
                strings = re.findall(r'"([^"]*)"', method["code"])
                string_literals.update(strings)
                
                # Extract number literals
                numbers = re.findall(r'\b(\d+(?:\.\d+)?)\b', method["code"])
                number_literals.update(numbers)
        
        # Calculate diversity factors
        assertion_diversity = min(1.0, len(assertion_types) / 5.0)  # Normalize to 5 types
        input_diversity = min(1.0, (len(string_literals) + len(number_literals)) / 20.0)  # Normalize to 20 literals
        method_coverage_factor = min(1.0, len(self.method_coverage) / 10.0)  # Normalize to 10 methods
        
        # Combined diversity score
        diversity_score = (
            0.4 * assertion_diversity +
            0.4 * input_diversity +
            0.2 * method_coverage_factor
        )
        
        self.test_diversity_score = diversity_score
        return diversity_score
    
    def evaluate(self, validator=None, verify_bugs=False, current_iteration=None):
        """
        Run tests and measure coverage with enhanced analytics
        
        Parameters:
        validator (TestValidator): Optional validator for fixing test code
        verify_bugs (bool): Whether to immediately verify bugs
        current_iteration (int): Current MCTS iteration number
        """
        # Call parent evaluate method first
        super().evaluate(validator, verify_bugs, current_iteration)
        
        if self.executed:
            try:
                # Calculate additional metrics
                self.calculate_test_diversity_score()
                
                # Analyze bug categories with expanded classification
                self._categorize_bugs_enhanced()
                
                # Verify bugs if requested
                if verify_bugs and self.detected_bugs:
                    self._verify_bugs()
                    
                # Calculate method complexity
                self._calculate_method_complexity()
                
                logger.debug(f"Enhanced evaluation: diversity={self.test_diversity_score:.2f}, " +
                          f"avg_assertions={self.avg_assertions_per_method:.2f}")
                
            except Exception as e:
                logger.error(f"Error in enhanced evaluation: {str(e)}")
                logger.error(traceback.format_exc())
    
    def _categorize_bugs_enhanced(self):
        """Categorize detected bugs with expanded classification"""
        # Reset counters
        self.bug_categories = defaultdict(int)
        self.bug_severity_counts = defaultdict(int)
        
        # Reset bug type tracking
        for category in self.bug_types:
            self.bug_types[category]["count"] = 0
            self.bug_types[category]["subtypes"] = defaultdict(int)
            self.bug_types[category]["verified_count"] = 0
        
        for bug in self.detected_bugs:
            # Basic categorization
            bug_type = bug.get("type", "unknown")
            self.bug_categories[bug_type] += 1
            
            # Categorize by severity
            severity = bug.get("severity", "medium")
            self.bug_severity_counts[severity] += 1
            
            # Enhanced categorization
            bug_category = bug.get("bug_category", self._infer_bug_category(bug))
            bug["bug_category"] = bug_category  # Ensure category is stored in bug info
            
            # Track in bug types structure
            if bug_category in self.bug_types:
                self.bug_types[bug_category]["count"] += 1
                
                # Track subtype if available
                subtype = bug.get("subtype", bug.get("bug_type", bug_type))
                self.bug_types[bug_category]["subtypes"][subtype] += 1
                
                # Track if verified
                if bug.get("verified", False) and bug.get("is_real_bug", False):
                    self.bug_types[bug_category]["verified_count"] += 1
    
    def _infer_bug_category(self, bug):
        """
        Infer bug category from error type and other info
        
        Parameters:
        bug (dict): Bug information
        
        Returns:
        str: Inferred bug category
        """
        error = bug.get("error", "")
        description = bug.get("description", "")
        
        # Check for resource management issues
        if ("ClosedChannelException" in error or 
            "IOException" in error or 
            "FileNotFoundException" in error or
            "leak" in description.lower() or
            "close" in description.lower()):
            return "resource_management"
            
        # Check for data operation issues
        if ("ClassCastException" in error or 
            "NumberFormatException" in error or
            "ArithmeticException" in error or
            "ArrayIndexOutOfBoundsException" in error or
            "conversion" in description.lower() or
            "overflow" in description.lower() or
            "underflow" in description.lower()):
            return "data_operation"
            
        # Check for exception handling issues
        if ("RuntimeException" in error or
            "try-catch" in description.lower() or
            "exception handling" in description.lower() or
            "swallowed" in description.lower() or
            "empty catch" in description.lower()):
            return "exception_handling"
            
        # Check for null pointer and validation issues
        if ("NullPointerException" in error or
            "IllegalArgumentException" in error or
            "validation" in description.lower() or
            "null check" in description.lower() or
            "empty check" in description.lower()):
            return "validation"
            
        # Check for concurrency issues
        if ("InterruptedException" in error or 
            "ConcurrentModificationException" in error or
            "concurrent" in description.lower() or
            "race condition" in description.lower() or
            "deadlock" in description.lower() or
            "thread" in description.lower()):
            return "concurrency"
            
        # Check for security issues
        if ("security" in description.lower() or
            "SQL" in description or
            "injection" in description.lower() or
            "credential" in description.lower() or
            "vulnerability" in description.lower()):
            return "security"
            
        # Default to logical for other bugs
        return "logical"
    
    def _verify_bugs(self):
        """
        Verify detected bugs and update metrics
        
        This is called when verify_bugs is True in evaluate()
        """
        self.verified_bugs = []
        
        for bug in self.detected_bugs:
            # Check if the bug has been verified
            if bug.get("verified", False):
                # If it's a real bug (verified positive), add to verified bugs list
                if bug.get("is_real_bug", False):
                    self.verified_bugs.append(bug)
    
    def _analyze_bug_likelihood(self, bug):
        """
        Analyze the likelihood that a detected bug is real
        
        Parameters:
        bug (dict): Bug information
        
        Returns:
        float: Likelihood (0.0 to 1.0) that the bug is real
        """
        # Start with moderate likelihood
        likelihood = 0.5
        
        # Check bug properties
        if bug.get("verified", False):
            # Already verified, use that result
            return 1.0 if bug.get("is_real_bug", False) else 0.0
        
        # Check assertion patterns
        if re.search(r'assert\w+', bug.get("description", "")):
            likelihood += 0.1
        
        # Check error type
        error_type = bug.get("error", "")
        if error_type in ["NullPointerException", "IndexOutOfBoundsException", "ClassCastException"]:
            likelihood += 0.1
        
        # Check test method code if available
        method_name = bug.get("test_method", "")
        for method in self.test_methods:
            if method.get("name") == method_name:
                method_code = method.get("code", "")
                
                # Check for thorough test method
                if len(re.findall(r'assert\w+', method_code)) > 2:
                    likelihood += 0.1
                if "try" in method_code and "catch" in method_code:
                    likelihood += 0.05
                
                break
        
        return min(likelihood, 1.0)
    
    def _calculate_method_complexity(self):
        """Calculate complexity metrics for test methods"""
        for method in self.test_methods:
            if isinstance(method, dict) and "code" in method:
                code = method.get("code", "")
                name = method.get("name", "unknown")
                
                # Simple complexity metrics
                lines = len(code.split("\n"))
                assertions = len(re.findall(r'assert\w+', code))
                branches = len(re.findall(r'if|else|for|while|switch|case', code))
                
                # Calculate complexity score
                complexity = 1 + (branches * 0.2) + (assertions * 0.1)
                
                # Store in test method complexity
                self.test_method_complexity[name] = {
                    "lines": lines,
                    "assertions": assertions,
                    "branches": branches,
                    "complexity_score": complexity
                }
    
    def calculate_method_coverage(self, coverage_data):
        """
        Calculate coverage per method
        
        Parameters:
        coverage_data (dict): Coverage data from JaCoCo
        """
        if not coverage_data:
            return
        
        # Extract method coverage if available
        method_coverage = coverage_data.get("methods", {})
        
        for method_name, coverage in method_coverage.items():
            self.method_coverage[method_name] = coverage
    
    def get_best_test_methods(self, max_methods=5):
        """
        Get the test methods with the highest quality score
        
        Parameters:
        max_methods (int): Maximum number of methods to return
        
        Returns:
        list: List of best test methods
        """
        if not self.test_methods:
            return []
        
        # Score each method based on:
        # 1. Assertion count
        # 2. Bug finding ability
        # 3. Coverage
        # 4. Complexity
        method_scores = {}
        
        for method in self.test_methods:
            if not isinstance(method, dict) or "name" not in method:
                continue
                
            name = method["name"]
            code = method.get("code", "")
            
            # Basic score
            score = 1.0
            
            # Assertions score
            assertions = len(re.findall(r'assert\w+', code))
            score += assertions * 0.5
            
            # Bug finding score - check if this method found a bug
            found_bug = False
            for bug in self.detected_bugs:
                if bug.get("test_method") == name:
                    found_bug = True
                    # Extra points for verified bugs
                    if bug.get("verified", False) and bug.get("is_real_bug", False):
                        score += 3.0
                    else:
                        score += 1.0
            
            # Coverage score
            if name in self.method_coverage:
                score += self.method_coverage[name] * 0.01  # 1 point per 100% coverage
            
            # Complexity score
            if name in self.test_method_complexity:
                complexity = self.test_method_complexity[name]["complexity_score"]
                # Reward moderate complexity, penalize extremely simple or complex
                if 1.0 <= complexity <= 3.0:
                    score += complexity * 0.3
                elif complexity > 3.0:
                    score += 0.9 - (complexity - 3.0) * 0.1  # Diminishing returns
            
            method_scores[name] = score
        
        # Sort methods by score
        sorted_methods = sorted(
            [(name, score) for name, score in method_scores.items()],
            key=lambda x: x[1],
            reverse=True
        )
        
        # Return top methods
        return [name for name, _ in sorted_methods[:max_methods]]
    
    def get_bug_distribution(self):
        """
        Get the distribution of bugs by category
        
        Returns:
        dict: Bug distribution by category
        """
        total_bugs = sum(self.bug_types[category]["count"] for category in self.bug_types)
        
        if total_bugs == 0:
            return {category: 0.0 for category in self.bug_types}
            
        distribution = {}
        for category in self.bug_types:
            count = self.bug_types[category]["count"]
            distribution[category] = count / total_bugs
            
        return distribution
    
    def get_bug_summary(self):
        """
        Get a summary of bugs found
        
        Returns:
        dict: Bug summary
        """
        return {
            "total_bugs": len(self.detected_bugs),
            "verified_bugs": len(self.verified_bugs),
            "categories": {
                category: {
                    "count": self.bug_types[category]["count"],
                    "verified_count": self.bug_types[category]["verified_count"],
                    "subtypes": dict(self.bug_types[category]["subtypes"])
                }
                for category in self.bug_types
            },
            "severity": dict(self.bug_severity_counts)
        }
    
    def get_bug_finding_methods_by_category(self, category=None):
        """
        Get test methods that found bugs in a specific category
        
        Parameters:
        category (str): Bug category to filter by
        
        Returns:
        list: Test methods that found bugs in the specified category
        """
        methods = []
        
        for bug in self.detected_bugs:
            # Skip if not in specified category
            if category and bug.get("bug_category") != category:
                continue
                
            # Skip if not verified
            if not bug.get("verified", False) or not bug.get("is_real_bug", False):
                continue
                
            method_name = bug.get("test_method")
            if method_name:
                # Find the method in test_methods
                for method in self.test_methods:
                    if method.get("name") == method_name:
                        methods.append(method)
                        break
        
        return methods