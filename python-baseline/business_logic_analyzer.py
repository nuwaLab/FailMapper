#!/usr/bin/env python3
"""
Business Logic Analyzer

This module analyzes Java code to identify potential business logic bugs by comparing
semantic intent (derived from method names, documentation, etc.) with actual implementation.
"""

import os
import re
import json
import logging
import javalang
from typing import Dict, Any, List
import traceback

# Import static analysis components
from semantic_analyzer import SemanticAnalyzer
from implementation_analyzer import ImplementationAnalyzer
from validation_engine import ValidationEngine

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("business_logic_analyzer")

class BusinessLogicAnalyzer:
    """
    Analyzes Java code to identify business logic inconsistencies
    by comparing semantic intent with actual implementation.
    """
    
    def __init__(self, source_code=None, class_name=None):
        """
        Initialize Business Logic Analyzer
        
        Parameters:
        source_code (str): Source code to analyze (optional)
        class_name (str): Class name (optional)
        """
        self.source_code = source_code
        self.class_name = class_name
        
        # Initialize analyzers
        self.semantic_analyzer = SemanticAnalyzer()
        self.implementation_analyzer = ImplementationAnalyzer()
        self.validation_engine = ValidationEngine()
        
        logger.info("Business Logic Analyzer initialized")
    
    def analyze_code_for_logic_bugs(self, source_code=None, class_name=None, method_name=None):
        """
        Analyze code for potential business logic bugs
        
        Parameters:
        source_code (str): Source code to analyze (optional if set in constructor)
        class_name (str): Class name (optional if set in constructor)
        method_name (str): Method name to analyze (required)
        
        Returns:
        dict: Analysis results including potential logical bugs
        """
        if source_code:
            self.source_code = source_code
        if class_name:
            self.class_name = class_name
            
        if not self.source_code or not method_name:
            logger.error("Source code and method name must be provided")
            return {"error": "Source code and method name must be provided"}
        
        try:
            # 1. Extract semantic intent signals
            semantic_signals = self.semantic_analyzer.extract_semantic_signals(
                self.source_code, self.class_name, method_name)
            
            # 2. Extract implementation features
            implementation_features = self.implementation_analyzer.extract_implementation_features(
                self.source_code, self.class_name, method_name)
            
            # 3. Create enhanced LLM prompt
            prompt = self._create_enhanced_prompt(semantic_signals, implementation_features)
            
            # 4. Send to LLM and get analysis
            llm_analysis = self._query_llm_for_analysis(prompt)

            # print("--------------------------------")
            # print("LLM Analysis:")
            # print(llm_analysis)
            # print("--------------------------------")
            
            # 5. Validate LLM results
            validated_results = self.validation_engine.validate_analysis(
                llm_analysis, implementation_features)
            
            # print("--------------------------------")
            # print("Validated Results:")
            # print(validated_results)
            # print("--------------------------------")
            
            # 6. Combine all results into final analysis
            final_analysis = {
                "method_name": method_name,
                "semantic_signals": semantic_signals,
                "implementation_features": implementation_features,
                "llm_analysis": llm_analysis,
                "validation": validated_results,
                "confidence": validated_results.get("overall_confidence", 0.0),
                "potential_bugs": self._identify_potential_bugs(llm_analysis, validated_results)
            }
            
            # print("--------------------------------")
            # print("Final Analysis:")
            # print(final_analysis)
            # print("--------------------------------")
            
            logger.info(f"Completed business logic analysis for method: {method_name}")
            return final_analysis
            
        except Exception as e:
            logger.error(f"Error in business logic analysis: {str(e)}")
            logger.error(traceback.format_exc())
            return {"error": str(e)}
    
    def _create_enhanced_prompt(self, semantic_signals, implementation_features):
        """
        Create enhanced LLM prompt with structured static analysis data
        
        Parameters:
        semantic_signals (dict): Semantic signals from code
        implementation_features (dict): Implementation features
        
        Returns:
        str: Enhanced prompt for LLM
        """
        # Format semantic signals in a structured way
        naming_info = semantic_signals.get("naming_signals", {})
        doc_info = semantic_signals.get("documentation_signals", {})
        type_info = semantic_signals.get("type_signals", {})
        
        method_name = naming_info.get("method_name", "unknown")
        method_tokens = naming_info.get("name_tokens", [])
        action_verb = naming_info.get("action_verb", "")
        
        # Format implementation features
        control_flow = implementation_features.get("control_flow", {})
        data_ops = implementation_features.get("data_operations", {})
        boundaries = implementation_features.get("boundary_conditions", [])
        algorithm = implementation_features.get("algorithmic_patterns", {})
        
        # Build the prompt
        prompt = f"""
        You are an expert Java code analyzer focused on finding business logic bugs.
        
        I'll provide you with information about a Java method and I need you to analyze if there's any mismatch between 
        what the method appears to intend to do (semantic intent) versus what it actually does (implementation).
        
        ## METHOD INFORMATION
        
        Method name: {method_name}
        Name tokens: {', '.join(method_tokens)}
        Primary action verb: {action_verb}
        Parameter names: {', '.join(naming_info.get('parameter_names', []))}
        Return type: {type_info.get('return_type', 'unknown')}
        Method documentation: {doc_info.get('javadoc', 'None')}
        
        ## IMPLEMENTATION DETAILS
        
        Control flow structure:
        - Conditions: {len(control_flow.get('conditions', []))}
        - Loops: {len(control_flow.get('loops', []))}
        - Branch complexity: {control_flow.get('complexity', 0)}
        
        Main algorithmic pattern: {algorithm.get('primary_pattern', 'Unknown')}
        Sorting direction: {algorithm.get('sorting_direction', 'N/A')}
        
        Boundary conditions:
        {self._format_boundary_conditions(boundaries)}
        
        Data operations:
        - Variables used: {', '.join(var['name'] for var in data_ops.get('variables', [])[:5])}
        - State changes: {len(data_ops.get('state_changes', []))}
        
        ## ANALYSIS TASK
        
        Please analyze if there's any mismatch between the semantic intent and the implementation, focusing on business logic bugs.
        
        Potential issues to look for:
        1. Method name suggests one behavior but code does another (e.g., name implies ascending but sorts descending)
        2. Parameter or return type suggests one purpose but code behaves differently
        3. Documentation describes one behavior but code implements another
        4. Inconsistent handling of edge cases
        5. Logical mistakes in core algorithm
        
        Provide your analysis in this format:
        1. SEMANTIC INTENT: What you believe the method is intended to do based on its name, parameters, etc.
        2. ACTUAL BEHAVIOR: What the implementation actually does
        3. MISMATCH ANALYSIS: Whether there's a mismatch and what it is
        4. CONFIDENCE: Your confidence level (1-10) that a business logic bug exists
        5. RECOMMENDATION: How to test for and fix this issue
        """
        return prompt
    
    def _format_boundary_conditions(self, boundaries):
        """Format boundary conditions for prompt"""
        if not boundaries:
            return "None found"
            
        formatted = []
        for i, boundary in enumerate(boundaries[:3]):  # Limit to first 3
            formatted.append(f"  {i+1}. {boundary.get('condition', 'Unknown')}")
            
        if len(boundaries) > 3:
            formatted.append(f"  ... and {len(boundaries) - 3} more")
            
        return "\n".join(formatted)
    
    def _query_llm_for_analysis(self, prompt):
        """
        Query LLM for analysis
        
        Parameters:
        prompt (str): Enhanced prompt
        
        Returns:
        dict: Parsed LLM analysis
        """
        try:
            # Import LLM API call function
            from feedback import call_anthropic_api, call_deepseek_api
            
            # Call LLM API
            raw_response = call_anthropic_api(prompt)
            # raw_response = call_deepseek_api(prompt)
            
            # Parse the structured response
            analysis = self._parse_llm_response(raw_response)
            
            return analysis
            
        except Exception as e:
            logger.error(f"Error querying LLM: {str(e)}")
            return {"error": str(e)}
    
    def _parse_llm_response(self, response):
        """
        Parse LLM response into structured format
        
        Parameters:
        response (str): Raw LLM response
        
        Returns:
        dict: Structured analysis
        """
        # Initialize structured result
        parsed = {
            "semantic_intent": "",
            "actual_behavior": "",
            "mismatch_analysis": "",
            "confidence": 0,
            "recommendation": "",
            "has_mismatch": False
        }
        
        # Extract sections
        sections = {
            "SEMANTIC INTENT": "semantic_intent",
            "ACTUAL BEHAVIOR": "actual_behavior",
            "MISMATCH ANALYSIS": "mismatch_analysis",
            "CONFIDENCE": "confidence_raw",
            "RECOMMENDATION": "recommendation"
        }
        
        for section_title, field in sections.items():
            pattern = rf"{section_title}:\s*(.*?)(?=\n\d+\.|$)"
            match = re.search(pattern, response, re.DOTALL)
            if match:
                parsed[field] = match.group(1).strip()
        
        # Process confidence
        if "confidence_raw" in parsed:
            # Extract numeric confidence
            confidence_match = re.search(r'(\d+(?:\.\d+)?)', parsed["confidence_raw"])
            if confidence_match:
                try:
                    parsed["confidence"] = float(confidence_match.group(1))
                    # Normalize to 0-1 scale
                    if parsed["confidence"] > 10:
                        parsed["confidence"] /= 100
                    elif parsed["confidence"] > 1:
                        parsed["confidence"] /= 10
                except:
                    parsed["confidence"] = 0.5
            
            # Remove raw field
            parsed.pop("confidence_raw", None)
        
        # Determine if mismatch exists
        mismatch_indicators = [
            "mismatch", "inconsistent", "doesn't match", "does not match",
            "contradiction", "incorrect", "bug", "error", "issue"
        ]
        parsed["has_mismatch"] = any(indicator in parsed["mismatch_analysis"].lower() 
                                    for indicator in mismatch_indicators)
        
        return parsed
    
    def _identify_potential_bugs(self, llm_analysis, validation):
        """
        Identify potential bugs from analysis
        
        Parameters:
        llm_analysis (dict): LLM analysis results
        validation (dict): Validation results
        
        Returns:
        list: Potential business logic bugs
        """
        bugs = []
        
        # Only consider as bug if validation confirms it with sufficient confidence
        # if (llm_analysis.get("has_mismatch", True) and 
        #     validation.get("is_bug_real", False) and
        #     validation.get("overall_confidence", 0) >= 0.6):
        if (llm_analysis.get("has_mismatch", True) and 
            any(value >= 0.6 for value in validation.values())):
            
            
            # Determine bug type
            bug_type = self._determine_bug_type(llm_analysis)
            
            # Extract test strategy from recommendation
            test_strategy = self._extract_test_strategy(llm_analysis.get("recommendation", ""))
            
            bugs.append({
                "type": bug_type,
                "description": llm_analysis.get("mismatch_analysis", "Undefined mismatch"),
                "confidence": validation.get("overall_confidence", 0.6),
                "expected_behavior": llm_analysis.get("semantic_intent", ""),
                "actual_behavior": llm_analysis.get("actual_behavior", ""),
                "test_strategy": test_strategy
            })
        
        return bugs
    
    def _determine_bug_type(self, llm_analysis):
        """
        Determine bug type from analysis
        
        Parameters:
        llm_analysis (dict): LLM analysis
        
        Returns:
        str: Bug type
        """
        mismatch = llm_analysis.get("mismatch_analysis", "").lower()
        intent = llm_analysis.get("semantic_intent", "").lower()
        actual = llm_analysis.get("actual_behavior", "").lower()
        
        # Look for specific patterns
        if any(word in mismatch for word in ["sort", "order", "asc", "desc"]):
            return "order_direction_mismatch"
        elif any(word in mismatch for word in ["bound", "index", "range"]):
            return "boundary_condition_error"
        elif any(word in mismatch for word in ["null", "empty"]):
            return "null_handling_error"
        elif any(word in mismatch for word in ["logic", "boolean", "condition"]):
            return "boolean_bug_error"
        elif any(word in mismatch for word in ["resource", "close", "leak"]):
            return "resource_management_error"
        else:
            return "general_logic_mismatch"
    
    def _extract_test_strategy(self, recommendation):
        """
        Extract test strategy from recommendation
        
        Parameters:
        recommendation (str): LLM recommendation
        
        Returns:
        str: Test strategy
        """
        # Look for test-related sentences
        test_patterns = [
            r'Test with .*?(?:\.|\n)',
            r'Create test .*?(?:\.|\n)',
            r'Verify that .*?(?:\.|\n)',
            r'Check .*?(?:\.|\n)'
        ]
        
        strategies = []
        for pattern in test_patterns:
            matches = re.findall(pattern, recommendation, re.IGNORECASE)
            strategies.extend(matches)
        
        if strategies:
            return " ".join(strategies)
        else:
            return "Test edge cases and boundary conditions to verify correct behavior"