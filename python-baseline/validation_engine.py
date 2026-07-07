#!/usr/bin/env python3
"""
Logic Validation Engine

This module validates LLM analysis results against static analysis
to improve accuracy of logical bug detection.
"""

import logging
from typing import Dict, Any

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("logic_validation_engine")

class ValidationEngine:
    """
    Validates LLM analysis against static analysis to improve accuracy
    """
    
    def __init__(self):
        """Initialize the validation engine"""
        pass
    
    def validate_analysis(self, llm_analysis: Dict[str, Any], implementation_features: Dict[str, Any]) -> Dict[str, Any]:
        """
        Validate LLM analysis against implementation features
        
        Parameters:
        llm_analysis (dict): LLM's analysis results
        implementation_features (dict): Extracted implementation features
        
        Returns:
        dict: Validation results
        """
        validation = {}
        
        # Validate control flow assessment
        validation["control_flow_accuracy"] = self._validate_control_flow_analysis(
            llm_analysis,
            implementation_features.get("control_flow", {})
        )
        
        # Validate data operations assessment
        validation["data_operations_accuracy"] = self._validate_data_operations_analysis(
            llm_analysis,
            implementation_features.get("data_operations", {})
        )
        
        # Validate boundary conditions assessment
        validation["boundary_accuracy"] = self._validate_boundary_analysis(
            llm_analysis,
            implementation_features.get("boundary_conditions", [])
        )
        
        # Validate algorithm pattern assessment
        validation["algorithm_accuracy"] = self._validate_algorithm_analysis(
            llm_analysis,
            implementation_features.get("algorithmic_patterns", {})
        )
        
        # Calculate overall confidence
        validation["overall_confidence"] = self._calculate_overall_confidence(validation)
        
        # Is bug real assessment
        validation["is_bug_real"] = self._assess_bug_reality(
            llm_analysis,
            implementation_features,
            validation["overall_confidence"]
        )
        
        return validation
    
    def _validate_control_flow_analysis(self, llm_analysis: Dict[str, Any], control_flow: Dict[str, Any]) -> float:
        """
        Validate control flow analysis
        
        Parameters:
        llm_analysis (dict): LLM's analysis
        control_flow (dict): Control flow features
        
        Returns:
        float: Accuracy score (0-1)
        """
        # Initialize accuracy score
        accuracy = 0.5  # Start with neutral score
        
        # Extract LLM's claims about control flow
        actual_behavior = llm_analysis.get("actual_behavior", "")
        explanation = llm_analysis.get("explanation", "")
        
        # Check if LLM correctly identified conditional logic
        if control_flow.get("if_count", 0) > 0:
            if "if" in actual_behavior.lower() or "condition" in actual_behavior.lower():
                accuracy += 0.1
        
        # Check if LLM correctly identified loops
        if control_flow.get("for_count", 0) + control_flow.get("while_count", 0) > 0:
            if "loop" in actual_behavior.lower() or "iterate" in actual_behavior.lower():
                accuracy += 0.1
        
        # Check if LLM correctly identified early returns
        if control_flow.get("has_early_returns", False):
            if "early return" in actual_behavior.lower() or "return" in actual_behavior.lower():
                accuracy += 0.1
        
        # Check if LLM correctly identified complexity
        if control_flow.get("cyclomatic_complexity", 0) > 5:
            if "complex" in actual_behavior.lower() or "multiple path" in actual_behavior.lower():
                accuracy += 0.1
        
        # Cap accuracy at 1.0
        return min(1.0, accuracy)
    
    def _validate_data_operations_analysis(self, llm_analysis: Dict[str, Any], data_operations: Dict[str, Any]) -> float:
        """
        Validate data operations analysis
        
        Parameters:
        llm_analysis (dict): LLM's analysis
        data_operations (dict): Data operations features
        
        Returns:
        float: Accuracy score (0-1)
        """
        # Initialize accuracy score
        accuracy = 0.5  # Start with neutral score
        
        # Extract LLM's claims about data operations
        actual_behavior = llm_analysis.get("actual_behavior", "")
        
        # Check if LLM correctly identified collection operations
        if "collection_operations" in data_operations.get("operations", {}):
            if ("add" in actual_behavior.lower() or 
                "insert" in actual_behavior.lower() or 
                "remove" in actual_behavior.lower() or 
                "collection" in actual_behavior.lower()):
                accuracy += 0.1
        
        # Check if LLM correctly identified string operations
        if "string_operations" in data_operations.get("operations", {}):
            if ("string" in actual_behavior.lower() or 
                "substr" in actual_behavior.lower() or 
                "concat" in actual_behavior.lower()):
                accuracy += 0.1
        
        # Check if LLM correctly identified math operations
        if "math_operations" in data_operations.get("operations", {}):
            if ("calculat" in actual_behavior.lower() or 
                "comput" in actual_behavior.lower() or 
                "math" in actual_behavior.lower()):
                accuracy += 0.1
        
        # Cap accuracy at 1.0
        return min(1.0, accuracy)
    
    def _validate_boundary_analysis(self, llm_analysis: Dict[str, Any], boundary_conditions: list) -> float:
        """
        Validate boundary conditions analysis
        
        Parameters:
        llm_analysis (dict): LLM's analysis
        boundary_conditions (list): Boundary conditions
        
        Returns:
        float: Accuracy score (0-1)
        """
        # Initialize accuracy score
        accuracy = 0.5  # Start with neutral score
        
        # Extract LLM's claims about boundary conditions
        actual_behavior = llm_analysis.get("actual_behavior", "")
        explanation = llm_analysis.get("explanation", "")
        potential_bugs = llm_analysis.get("potential_bugs", [])
        
        # Check if LLM correctly identified null checks
        null_checks = [c for c in boundary_conditions if c.get("is_null_check", False)]
        if null_checks:
            if ("null" in actual_behavior.lower() or 
                "null" in explanation.lower() or
                any("null" in bug.lower() for bug in potential_bugs)):
                accuracy += 0.1
        
        # Check if LLM correctly identified length/size checks
        length_checks = [c for c in boundary_conditions if c.get("is_length_check", False)]
        if length_checks:
            if ("length" in actual_behavior.lower() or 
                "size" in actual_behavior.lower() or
                "empty" in actual_behavior.lower() or
                any(("length" in bug.lower() or "size" in bug.lower()) for bug in potential_bugs)):
                accuracy += 0.1
        
        # Check if LLM correctly identified zero checks
        zero_checks = [c for c in boundary_conditions if c.get("is_zero_check", False)]
        if zero_checks:
            if ("zero" in actual_behavior.lower() or 
                "0" in explanation.lower() or
                any("zero" in bug.lower() for bug in potential_bugs)):
                accuracy += 0.1
        
        # Check if LLM correctly identified array access boundary issues
        array_accesses = [c for c in boundary_conditions if c.get("type") == "array_access"]
        unsafe_array_accesses = [c for c in array_accesses if not c.get("has_boundary_check", False)]
        if unsafe_array_accesses:
            if ("array" in actual_behavior.lower() or 
                "index" in actual_behavior.lower() or
                "bound" in actual_behavior.lower() or
                any(("array" in bug.lower() or "index" in bug.lower()) for bug in potential_bugs)):
                accuracy += 0.1
        
        # Cap accuracy at 1.0
        return min(1.0, accuracy)
    
    def _validate_algorithm_analysis(self, llm_analysis: Dict[str, Any], algorithm_patterns: Dict[str, Any]) -> float:
        """
        Validate algorithm pattern analysis
        
        Parameters:
        llm_analysis (dict): LLM's analysis
        algorithm_patterns (dict): Algorithm patterns
        
        Returns:
        float: Accuracy score (0-1)
        """
        # Initialize accuracy score
        accuracy = 0.5  # Start with neutral score
        
        # Extract LLM's claims about algorithms
        actual_behavior = llm_analysis.get("actual_behavior", "")
        explanation = llm_analysis.get("explanation", "")
        intended_behavior = llm_analysis.get("intended_behavior", "")
        
        # Check if LLM correctly identified sorting
        if "sorting" in algorithm_patterns:
            if "sort" in actual_behavior.lower() or "order" in actual_behavior.lower():
                accuracy += 0.1
                
                # Check if LLM correctly identified sorting direction
                if "sorting_direction" in algorithm_patterns:
                    direction = algorithm_patterns["sorting_direction"]
                    if direction == "ascending":
                        if ("ascend" in actual_behavior.lower() or 
                            "increasing" in actual_behavior.lower() or 
                            "smallest to largest" in actual_behavior.lower()):
                            accuracy += 0.1
                    elif direction == "descending":
                        if ("descend" in actual_behavior.lower() or 
                            "decreasing" in actual_behavior.lower() or 
                            "largest to smallest" in actual_behavior.lower()):
                            accuracy += 0.1
        
        # Check if LLM correctly identified searching
        if "searching" in algorithm_patterns:
            if "search" in actual_behavior.lower() or "find" in actual_behavior.lower():
                accuracy += 0.1
        
        # Check if LLM correctly identified data processing
        if "data_processing" in algorithm_patterns:
            if ("process" in actual_behavior.lower() or 
                "transform" in actual_behavior.lower() or 
                "filter" in actual_behavior.lower() or 
                "map" in actual_behavior.lower()):
                accuracy += 0.1
        
        # Check if LLM correctly identified algorithm-name mismatch
        if "sorting" in algorithm_patterns or "searching" in algorithm_patterns:
            # Check if method name doesn't match what it does
            if ("name suggests" in explanation.lower() or 
                "misleading name" in explanation.lower() or 
                "name implies" in explanation.lower()):
                
                # Check if sorting direction is inconsistent with name
                if ("sorting_direction" in algorithm_patterns and
                    ("sort" in intended_behavior.lower() or "order" in intended_behavior.lower())):
                    
                    direction = algorithm_patterns["sorting_direction"]
                    expected_direction = "ascending"
                    
                    # Check method name for direction hints
                    if ("desc" in intended_behavior.lower() or 
                        "decreasing" in intended_behavior.lower() or 
                        "reverse" in intended_behavior.lower()):
                        expected_direction = "descending"
                    
                    # If mismatch detected by both LLM and static analysis
                    if (direction != expected_direction and 
                        "mismatch" in explanation.lower()):
                        accuracy += 0.2
        
        # Cap accuracy at 1.0
        return min(1.0, accuracy)
    
    def _calculate_overall_confidence(self, validation: Dict[str, float]) -> float:
        """
        Calculate overall confidence based on component validations
        
        Parameters:
        validation (dict): Validation results
        
        Returns:
        float: Overall confidence score (0-1)
        """
        # Get component scores
        control_flow_accuracy = validation.get("control_flow_accuracy", 0.5)
        data_operations_accuracy = validation.get("data_operations_accuracy", 0.5)
        boundary_accuracy = validation.get("boundary_accuracy", 0.5)
        algorithm_accuracy = validation.get("algorithm_accuracy", 0.5)
        
        # Calculate weighted average
        # Weight boundary and algorithm analysis more heavily
        weights = {
            "control_flow_accuracy": 0.2,
            "data_operations_accuracy": 0.2,
            "boundary_accuracy": 0.3,
            "algorithm_accuracy": 0.3
        }
        
        overall_confidence = (
            control_flow_accuracy * weights["control_flow_accuracy"] +
            data_operations_accuracy * weights["data_operations_accuracy"] +
            boundary_accuracy * weights["boundary_accuracy"] +
            algorithm_accuracy * weights["algorithm_accuracy"]
        )
        
        return overall_confidence
    
    def _assess_bug_reality(self, llm_analysis: Dict[str, Any], implementation_features: Dict[str, Any], confidence: float) -> bool:
        """
        Assess whether bug is likely real based on analysis and confidence
        
        Parameters:
        llm_analysis (dict): LLM's analysis
        implementation_features (dict): Implementation features
        confidence (float): Overall confidence
        
        Returns:
        bool: True if bug is likely real, False otherwise
        """
        # Get bug information
        potential_bugs = llm_analysis.get("potential_bugs", [])
        
        # If high confidence and potential bugs exist, likely real
        if confidence >= 0.7 and potential_bugs:
            return True
            
        # Check for specific high-confidence patterns
        
        # 1. Check for sort direction mismatch
        if (implementation_features.get("algorithmic_patterns", {}).get("sorting_direction") and
            "sort" in llm_analysis.get("intended_behavior", "").lower()):
            
            # Check if intended and actual behavior contradict on sort direction
            intended = llm_analysis.get("intended_behavior", "").lower()
            actual = llm_analysis.get("actual_behavior", "").lower()
            
            direction = implementation_features["algorithmic_patterns"]["sorting_direction"]
            
            # Detect mismatch
            if ((direction == "ascending" and 
                 (("descend" in intended or "decreasing" in intended) and 
                  ("ascend" in actual or "increasing" in actual))) or
                (direction == "descending" and 
                 (("ascend" in intended or "increasing" in intended) and 
                  ("descend" in actual or "decreasing" in actual)))):
                return True
        
        # 2. Check for boundary bug patterns
        boundary_conditions = implementation_features.get("boundary_conditions", [])
        array_accesses = [c for c in boundary_conditions if c.get("type") == "array_access"]
        unsafe_array_accesses = [c for c in array_accesses if not c.get("has_boundary_check", False)]
        
        if unsafe_array_accesses and "index" in " ".join(potential_bugs).lower():
            return True
        
        # 3. Check for null handling issues
        null_checks = [c for c in boundary_conditions if c.get("is_null_check", False)]
        error_handling = implementation_features.get("error_handling", [])
        
        if not null_checks and "null" in " ".join(potential_bugs).lower():
            # Check if method uses objects that could be null
            operations = implementation_features.get("data_operations", {}).get("operations", {})
            for category, ops in operations.items():
                for op in ops:
                    # If operating on variables without null checks
                    if op.get("variable") and not any(nc.get("variable") == op.get("variable") for nc in null_checks):
                        return True
        
        # Default to lower confidence assessment
        return confidence >= 0.8 and bool(potential_bugs)