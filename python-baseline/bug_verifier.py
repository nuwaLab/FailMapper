#!/usr/bin/env python3
"""
Bug Verifier

This module extends bug verification capabilities with specialized analysis
for bugs. It provides more sophisticated verification of bugs
using pattern matching, code context analysis, and LLM-based verification.
"""

import os
import re
import json
import logging
import traceback
from collections import defaultdict

# Import from base bug verification
from verify_bug_with_llm import verify_bug_with_llm

# Import for API access
from feedback import call_anthropic_api, call_gpt_api, call_deepseek_api, extract_java_code

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("bug_verifier")

class BugVerifier:
    """
    Provides specialized verification for bugs with enhanced
    analysis of properties, boundary conditions, and code context.
    """
    
    def __init__(self, source_code, class_name, package_name):
        """
        Initialize with source code to analyze
        
        Parameters:
        source_code (str): Source code to analyze
        class_name (str): Class name
        package_name (str): Package name
        """
        self.source_code = source_code
        self.class_name = class_name
        self.package_name = package_name
        
        # Logic bug patterns with verification strategies
        # self.logic_bug_categories = {
        #     "incorrect_value": {
        #         "verify_method": self._verify_incorrect_value,
        #         "confidence_threshold": 0.7
        #     },
        #     "incorrect_boolean": {
        #         "verify_method": self._verify_incorrect_boolean,
        #         "confidence_threshold": 0.8
        #     },
        #     "empty_null_handling": {
        #         "verify_method": self._verify_empty_null_handling,
        #         "confidence_threshold": 0.6
        #     },
        #     "index_error": {
        #         "verify_method": self._verify_index_error,
        #         "confidence_threshold": 0.8
        #     },
        #     "string_index_error": {  # 添加新的字符串索引错误验证
        #         "verify_method": self._verify_string_index_error,
        #         "confidence_threshold": 0.8
        #     },
        #     "array_index_error": {  # 添加新的数组索引错误验证
        #         "verify_method": self._verify_array_index_error,
        #         "confidence_threshold": 0.8
        #     },
        #     "null_reference": {
        #         "verify_method": self._verify_null_reference,
        #         "confidence_threshold": 0.7
        #     },
        #     "boundary_error": {
        #         "verify_method": self._verify_boundary_error,
        #         "confidence_threshold": 0.8
        #     },
        #     "operator_logic": {
        #         "verify_method": self._verify_operator_bug,
        #         "confidence_threshold": 0.8
        #     },
        #     "boolean_bug": {
        #         "verify_method": self._verify_boolean_bug,
        #         "confidence_threshold": 0.7
        #     },
        #     "infinite_loop": {
        #         "verify_method": self._verify_infinite_loop,
        #         "confidence_threshold": 0.9
        #     },
        #     "resource_leak": {
        #         "verify_method": self._verify_resource_leak,
        #         "confidence_threshold": 0.8
        #     },
        #     "resource_management": {
        #         "verify_method": self._verify_resource_management,
        #         "confidence_threshold": 0.7
        #     },
        #     "use_after_close": {
        #         "verify_method": self._verify_use_after_close,
        #         "confidence_threshold": 0.8
        #     },
        #     "data_operation": {
        #         "verify_method": self._verify_data_operation,
        #         "confidence_threshold": 0.7
        #     },
        #     "integer_truncation": {
        #         "verify_method": self._verify_integer_truncation,
        #         "confidence_threshold": 0.8
        #     },
        #     "precision_loss": {
        #         "verify_method": self._verify_precision_loss,
        #         "confidence_threshold": 0.7
        #     },
        #     "exception_handling": {
        #         "verify_method": self._verify_exception_handling,
        #         "confidence_threshold": 0.7
        #     },
        #     "swallowed_exception": {
        #         "verify_method": self._verify_swallowed_exception,
        #         "confidence_threshold": 0.8
        #     },
        #     "validation": {
        #         "verify_method": self._verify_validation,
        #         "confidence_threshold": 0.7 
        #     },
        #     "concurrency": {
        #         "verify_method": self._verify_concurrency,
        #         "confidence_threshold": 0.8
        #     },
        #     "security": {
        #         "verify_method": self._verify_security,
        #         "confidence_threshold": 0.8
        #     }
        # }
        
        # Counter for verification results
        self.verification_results = defaultdict(int)
    
    def verify_bugs(self, bug_methods):
        """
        verify a list of bug test methods, ensuring no duplicate counting of the same bug
        
        Parameters:
        bug_methods (list): the list of bug test methods to verify
        
        Returns:
        list: the list of verified bug test methods
        """
        import hashlib
        
        # use a dictionary to deduplicate
        verified_methods_dict = {}
        
        if not bug_methods:
            return []
            
        logger.info(f"using failure-aware verification for {len(bug_methods)} bug methods")
        
        # reset the verification counter
        self.verification_results = defaultdict(int)
        
        try:
            # process each bug method
            for method in bug_methods:
                if not isinstance(method, dict) or "code" not in method:
                    continue
                    
                # get or create the bug signature
                bug_signature = method.get("bug_signature")
                if not bug_signature:
                    # create the signature
                    method_name = method.get("method_name", "unknown")
                    error = method.get("error", "")
                    bug_signature = f"{method_name}:{hashlib.md5(error.encode()).hexdigest()[:12]}"
                    
                # if the signature has been processed, skip
                if bug_signature in verified_methods_dict:
                    logger.info(f"skipping verified bug signature: {bug_signature}")
                    continue
                    
                # extract the method information
                # print("--------------------------------")
                # print(method)
                # print("--------------------------------")
                method_code = method.get("code", "")
                bug_type = method['bug_info'][0].get("bug_type", "unknown")
                method_name = method.get("method_name", "unknown")
                
                logger.info(f"verifying method '{method_name}', bug type: {bug_type}")
                
                # use LLM to verify the bug
                verification_result = verify_bug_with_llm(
                    method, method_code, self.source_code, self.class_name
                )
                
                # explicitly set is_real_bug, not relying on the default value
                is_real_bug = verification_result.get("is_real_bug", False)
                
                # merge the verification results into the method
                method_result = {
                    **method,
                    "verified": True,
                    "is_real_bug": is_real_bug,
                    "verification_confidence": verification_result.get("confidence", 0.5),
                    "verification_reasoning": verification_result.get("reasoning", "逻辑bug验证")
                }
                
                # add to the result dictionary
                verified_methods_dict[bug_signature] = method_result
                
                # track the verification results
                if is_real_bug:
                    self.verification_results["true_positive"] += 1
                else:
                    self.verification_results["false_positive"] += 1
                    
            # convert back to a list
            verified_methods = list(verified_methods_dict.values())
            
            # record the verification summary
            total_verified = len(verified_methods)
            true_positives = self.verification_results["true_positive"]
            false_positives = self.verification_results["false_positive"]
            
            # record the verification summary
            logger.info(f"验证完成: " +
                    f"{true_positives} 真实bug, " +
                    f"{false_positives} 误报, " +
                    f"{total_verified} 总方法数")
            
            return verified_methods
            
        except Exception as e:
            logger.error(f"验证bug时出错: {str(e)}")
            logger.error(traceback.format_exc())
            return []
    

    def _standard_verification(self, method_code, method_info):
        """
        Standard verification using base LLM verification
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        try:
            return verify_bug_with_llm(method_info, method_code, self.source_code, self.class_name)
        except Exception as e:
            logger.error(f"Error in standard verification: {str(e)}")
            # Return conservative result (assume it might be a bug but with low confidence)
            return {
                "is_real_bug": method_info.get("confidence", 0.0) > 0.7,
                "confidence": 0.5,
                "reasoning": f"Verification failed with error: {str(e)}"
            }
    
    def _verify_business_logic(self, method_code, method_info):
        """
        Verify business logic bugs using business logic analyzer
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Import business logic analyzer
        from business_logic_analyzer import BusinessLogicAnalyzer
        
        # Extract method name
        method_name = method_info.get("method_name", "unknown")
        
        # Get related source code for the method being tested
        source_context = self._extract_related_source_code(method_name)
        
        # Create an instance of business logic analyzer
        analyzer = BusinessLogicAnalyzer(self.source_code, self.class_name)
        
        # Analyze the method's business logic
        analysis = analyzer.analyze_code_for_logic_bugs(
            source_code=self.source_code,
            class_name=self.class_name,
            method_name=method_name
        )
        
        # If analysis failed, fall back to standard verification
        if "error" in analysis:
            logger.warning(f"Business logic analysis failed: {analysis['error']}")
            return self._standard_verification(method_code, method_info)
        
        # Extract validation and confidence from analysis
        validation = analysis.get("validation", {})
        confidence = validation.get("overall_confidence", 0.0)
        is_bug_real = validation.get("is_bug_real", False)
        
        # Get potential bugs and explanation
        potential_bugs = analysis.get("potential_bugs", [])
        explanation = analysis.get("llm_analysis", {}).get("explanation", "")
        
        # If confidence is high enough and bug is considered real
        if confidence >= 0.7 and is_bug_real and potential_bugs:
            return {
                "is_real_bug": True,
                "confidence": confidence,
                "reasoning": f"Business logic analysis found {len(potential_bugs)} potential bugs. {explanation}"
            }
        elif confidence >= 0.6:
            # Medium confidence - could be a bug but not certain
            return {
                "is_real_bug": is_bug_real,
                "confidence": confidence,
                "reasoning": f"Business logic analysis with medium confidence: {explanation}"
            }
        else:
            # Low confidence - fall back to standard verification
            return self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "business_logic",
                f"Business logic analysis was inconclusive with confidence {confidence:.2f}. {explanation}"
            )

    def _verify_incorrect_value(self, method_code, method_info):
        """
        Verify an incorrect value bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Look for assertion with expected vs actual values
        expected_actual_match = re.search(r'expected:.*?<([^>]+)>.*?but was:.*?<([^>]+)>', method_code)
        
        if expected_actual_match:
            expected = expected_actual_match.group(1)
            actual = expected_actual_match.group(2)
            
            # Check for empty/null values
            if expected == "" and actual == "null" or expected == "null" and actual == "":
                # Common false positive - different representations of empty
                return {
                    "is_real_bug": False,
                    "confidence": 0.8,
                    "reasoning": "Empty string vs null is likely not a real bug, but a test expectation issue"
                }
            
            # Check for boolean confusions
            if (expected.lower() == "true" and actual.lower() == "false") or \
               (expected.lower() == "false" and actual.lower() == "true"):
                # Likely a real logic bug
                return {
                    "is_real_bug": True,
                    "confidence": 0.8,
                    "reasoning": "Boolean value mismatch often indicates a logical bug in conditional logic"
                }
            
            # Check for off-by-one situations
            try:
                if expected.isdigit() and actual.isdigit():
                    expected_num = int(expected)
                    actual_num = int(actual)
                    if abs(expected_num - actual_num) == 1:
                        # Classic off-by-one error
                        return {
                            "is_real_bug": True,
                            "confidence": 0.9,
                            "reasoning": "Off-by-one difference between expected and actual numeric values"
                        }
            except:
                pass
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "incorrect_value")
    
    def _verify_incorrect_boolean(self, method_code, method_info):
        """
        Verify a boolean logic bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Look for pattern of boolean test failure
        boolean_failure = re.search(r'expected:.*?<(true|false)>.*?but was:.*?<(true|false)>', method_code, re.IGNORECASE)
        
        if boolean_failure:
            expected = boolean_failure.group(1).lower()
            actual = boolean_failure.group(2).lower()
            
            if expected != actual:
                # Extract the assertion context
                assertion_context = self._extract_assertion_context(method_code)
                
                # Check for negation or complex conditions
                if "!" in assertion_context or "&&" in assertion_context or "||" in assertion_context:
                    # Likely a real bug in boolean expression
                    return {
                        "is_real_bug": True,
                        "confidence": 0.9,
                        "reasoning": "Boolean condition failure with complex expression or negation"
                    }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "boolean_bug")
    
    def _verify_empty_null_handling(self, method_code, method_info):
        """
        Verify empty/null handling bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Look for null-related assertions
        null_check = re.search(r'assert(?:Equals|Null|NotNull)\s*\([^,]*null', method_code)
        empty_check = re.search(r'assert(?:Equals|True|False)\s*\([^,]*(?:""|isEmpty\(\))', method_code)
        
        if null_check or empty_check:
            # Look for specific null handling in source code
            method_name = method_info.get("method_name", "unknown")
            source_context = self._extract_related_source_code(method_name)
            
            # Check if source code has null checks
            if "!= null" in source_context or "== null" in source_context:
                # Source has null checks, test might have found a real issue
                return {
                    "is_real_bug": True,
                    "confidence": 0.7,
                    "reasoning": "Source code contains null checks, test failure could indicate mishandling"
                }
            
            # Check for empty string handling
            if '""' in source_context or "isEmpty()" in source_context:
                # Source handles empty strings, test might have found a real issue
                return {
                    "is_real_bug": True,
                    "confidence": 0.7,
                    "reasoning": "Source code contains empty string handling, test failure could indicate issue"
                }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "empty_null_handling")
    
    def _verify_string_index_error(self, method_code, method_info):
        """
        Verify string index error bugs with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Check for StringIndexOutOfBoundsException
        string_exception = re.search(r'StringIndexOutOfBounds', method_code)
        
        if string_exception:
            # High confidence if explicitly testing for this exception
            if re.search(r'assertThrows\s*\(\s*.*StringIndexOutOfBoundsException', method_code):
                return {
                    "is_real_bug": True,
                    "confidence": 0.95,
                    "reasoning": "Test explicitly checks for StringIndexOutOfBoundsException, almost certainly a real issue"
                }
            return {
                "is_real_bug": True,
                "confidence": 0.9,
                "reasoning": "StringIndexOutOfBoundsException mentioned in test, likely a real bug"
            }
        
        # Look for string operations
        charAt_usage = re.search(r'(\w+)\.charAt\s*\(\s*([^)]+)\s*\)', method_code)
        substring_usage = re.search(r'(\w+)\.substring\s*\(\s*([^,)]+)(?:\s*,\s*([^)]+))?\s*\)', method_code)
        
        if charAt_usage or substring_usage:
            # Extract usage info
            if charAt_usage:
                string_var = charAt_usage.group(1)
                index_expr = charAt_usage.group(2)
            else:  # substring_usage
                string_var = substring_usage.group(1)
                index_expr = substring_usage.group(2)
            
            # Check if test creates edge cases
            edge_cases = re.search(r'length\(\s*\)\s*[+-]\s*1|empty string|""', method_code)
            if edge_cases:
                # Test is likely checking edge cases for string operations
                method_name = method_info.get("method_name", "unknown")
                source_context = self._extract_related_source_code(method_name)
                
                # Check source for length validations before index access
                if not re.search(fr'if\s*\([^)]*{string_var}\.length\(\)[^)]*\)', source_context):
                    return {
                        "is_real_bug": True,
                        "confidence": 0.85,
                        "reasoning": "Test checks edge cases for string operations but source lacks length validation"
                    }
            
            # Check for explicit string index validation in test
            if re.search(r'(assertEquals|assertTrue|assertFalse)[^;]*StringIndexOutOfBounds', method_code):
                # Test is explicitly checking for index validation
                return {
                    "is_real_bug": True,
                    "confidence": 0.9,
                    "reasoning": "Test explicitly checks string index validation behavior"
                }
        
        # Look for test cases with empty strings
        empty_strings = re.search(r'(?:String\s+\w+\s*=\s*""|=\s*new\s+String\s*\(\s*\))', method_code)
        if empty_strings and (charAt_usage or substring_usage):
            return {
                "is_real_bug": True, 
                "confidence": 0.8,
                "reasoning": "Test uses empty strings with string operations that could cause index errors"
            }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "string_index_error", 
                                            "This test may be checking for StringIndexOutOfBoundsException issues.")

    def _verify_index_error(self, method_code, method_info):
        """
        Verify index error bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Check for index-related exceptions
        index_exception = re.search(r'IndexOutOfBoundsException|ArrayIndexOutOfBoundsException|StringIndexOutOfBoundsException', method_code)
        
        if index_exception:
            # If we see specific array or string exceptions, delegate to specialized verifiers
            if "ArrayIndexOutOfBoundsException" in method_code:
                return self._verify_array_index_error(method_code, method_info)
            elif "StringIndexOutOfBoundsException" in method_code:
                return self._verify_string_index_error(method_code, method_info)
                
            # Generic IndexOutOfBoundsException - high confidence
            return {
                "is_real_bug": True,
                "confidence": 0.9,
                "reasoning": "Index out of bounds exceptions are almost always real bugs"
            }
        
        # Look for array index patterns
        array_access = re.search(r'(\w+)\s*\[\s*([^]]+)\s*\]', method_code)
        
        if array_access:
            array_name = array_access.group(1)
            index_expr = array_access.group(2)
            
            # Check if index looks like a boundary case
            if index_expr.isdigit() or ".length" in index_expr or "-1" in index_expr:
                method_name = method_info.get("method_name", "unknown")
                source_context = self._extract_related_source_code(method_name)
                
                # Check source for boundary checks
                if re.search(r'if\s*\([^)]*(?:<|>|<=|>=)[^)]*(?:length|size)', source_context):
                    # Source has boundary checks, test might have found a real issue
                    return {
                        "is_real_bug": True,
                        "confidence": 0.8,
                        "reasoning": "Source code contains boundary checks, index issue might be real"
                    }
        
        # Look for collection access patterns (List.get(), etc.)
        collection_access = re.search(r'(\w+)\.(?:get|set|remove)\s*\(\s*([^,)]+)\s*[,)]', method_code)
        if collection_access:
            collection_name = collection_access.group(1)
            index_expr = collection_access.group(2)
            
            # Check for boundary validation
            method_name = method_info.get("method_name", "unknown")
            source_context = self._extract_related_source_code(method_name)
            
            if not re.search(fr'{collection_name}\.size\(\)', source_context):
                return {
                    "is_real_bug": True,
                    "confidence": 0.75,
                    "reasoning": "Collection access without size validation in source code"
                }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "index_error")

    def _verify_null_reference(self, method_code, method_info):
        """
        Verify null reference bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Check for NullPointerException
        null_exception = re.search(r'NullPointerException', method_code)
        
        if null_exception:
            # Extract test setup to find potential null values
            setup_section = re.search(r'(?:void\s+\w+\s*\([^)]*\)\s*\{)(.*?)(?:assert)', method_code, re.DOTALL)
            
            if setup_section:
                setup_code = setup_section.group(1)
                
                # Check if test explicitly sets null values
                if "= null" in setup_code:
                    # Test explicitly sets null, likely testing null handling
                    return {
                        "is_real_bug": True,
                        "confidence": 0.8,
                        "reasoning": "Test explicitly sets null values and triggers NullPointerException"
                    }
                
                # Check source for null checks
                method_name = method_info.get("method_name", "unknown")
                source_context = self._extract_related_source_code(method_name)
                
                if "== null" not in source_context and "!= null" not in source_context:
                    # Source doesn't have null checks, likely a real issue
                    return {
                        "is_real_bug": True,
                        "confidence": 0.9,
                        "reasoning": "Source code doesn't check for null values but should"
                    }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "null_reference")
    
    def _verify_boundary_error(self, method_code, method_info):
        """
        Verify boundary error bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract boundary value tests
        boundary_tests = re.findall(r'assert(?:True|False|Equals)\s*\(\s*[^<>=!]+\s*([<>=!]+)\s*([^,\)]+)', method_code)
        boundary_contexts = []
        
        for op, value in boundary_tests:
            boundary_contexts.append(f"Testing boundary with {op} {value.strip()}")
        
        # Look for typical boundary error patterns
        if ">=" in method_code and "<=" in method_code:
            # Test is checking both upper and lower bounds, likely a boundary test
            
            # Check source for boundary checks
            method_name = method_info.get("method_name", "unknown")
            source_context = self._extract_related_source_code(method_name)
            
            if re.search(r'if\s*\([^)]*(?:<|>|<=|>=)[^)]*\)', source_context):
                # Source has boundary checks, test might have found a real issue
                return {
                    "is_real_bug": True,
                    "confidence": 0.8,
                    "reasoning": "Source code contains boundary checks, boundary test may have found real issue"
                }
        
        # Check for off-by-one patterns
        if "-1" in method_code or "+1" in method_code:
            # Test is using -1/+1 adjustments, likely testing boundaries
            
            # Check for assertion failures
            if self._has_assertion_failure(method_info):
                # Failed assertion with boundary test is likely a real bug
                return {
                    "is_real_bug": True,
                    "confidence": 0.7,
                    "reasoning": "Off-by-one test with assertion failure indicates boundary issue"
                }
        
        # Use LLM for deeper analysis with boundary contexts
        return self._enhanced_llm_verification(method_code, method_info, "boundary_error", 
                                              additional_context="\n".join(boundary_contexts))
    
    def _verify_array_index_error(self, method_code, method_info):
        """
        Verify array index error bugs with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Check for ArrayIndexOutOfBoundsException
        array_exception = re.search(r'ArrayIndexOutOfBoundsException', method_code)
        
        if array_exception:
            # High confidence if explicitly testing for this exception
            if re.search(r'assertThrows\s*\(\s*.*ArrayIndexOutOfBoundsException', method_code):
                return {
                    "is_real_bug": True,
                    "confidence": 0.95,
                    "reasoning": "Test explicitly checks for ArrayIndexOutOfBoundsException, almost certainly a real issue"
                }
            return {
                "is_real_bug": True,
                "confidence": 0.9,
                "reasoning": "ArrayIndexOutOfBoundsException mentioned in test, likely a real bug"
            }
        
        # Look for array accesses with indices that could be problematic
        array_patterns = [
            r'(\w+)\s*\[\s*(-\d+)\s*\]',                              # Negative index
            r'(\w+)\s*\[\s*(\w+)\.length\s*\]',                       # Index at length (should be length-1)
            r'(\w+)\s*\[\s*(\w+\s*(?:\+\+|--|\+=|-=).*?)\s*\]',       # Modified index in access
            r'for\s*\([^;]*;\s*\w+\s*<=\s*(\w+)\.length\s*;[^{]*\{\s*[^}]*\1\s*\[\s*(\w+)\s*\]'  # Loop with <= length
        ]
        
        for pattern in array_patterns:
            matches = re.finditer(pattern, method_code)
            for match in matches:
                # We found a potentially problematic array access pattern
                
                # Check if the test is verifying correct behavior or finding bugs
                method_name = method_info.get("method_name", "unknown")
                source_context = self._extract_related_source_code(method_name)
                
                # Look for validation in source code
                has_validation = re.search(r'if\s*\([^)]*<\s*\w+\.length', source_context)
                
                if not has_validation:
                    return {
                        "is_real_bug": True,
                        "confidence": 0.85,
                        "reasoning": "Test appears to identify array access without proper bounds checking in source code"
                    }
        
        # Check for multi-dimensional array accesses in tests
        multi_dim_array = re.search(r'(\w+)\s*\[\s*([^]]+)\s*\]\s*\[\s*([^]]+)\s*\]', method_code)
        if multi_dim_array:
            array_name = multi_dim_array.group(1)
            first_idx = multi_dim_array.group(2)
            second_idx = multi_dim_array.group(3)
            
            # Check source code for validation of both dimensions
            method_name = method_info.get("method_name", "unknown")
            source_context = self._extract_related_source_code(method_name)
            
            first_dim_check = re.search(fr'if\s*\([^)]*{array_name}\.length', source_context)
            second_dim_check = re.search(fr'if\s*\([^)]*{array_name}\[\s*[^\]]+\s*\]\.length', source_context)
            
            if not (first_dim_check and second_dim_check):
                return {
                    "is_real_bug": True,
                    "confidence": 0.8,
                    "reasoning": "Test with multi-dimensional array access, but source lacks complete boundary checks"
                }
        
        # Check for tests with edge cases like empty arrays or boundary indices
        empty_array_test = re.search(r'new\s+\w+\s*\[\s*0\s*\]|emptyArray', method_code, re.IGNORECASE)
        boundary_test = re.search(r'\.length\s*-\s*1|\[\s*0\s*\]', method_code)
        
        if (empty_array_test or boundary_test) and "assertThrows" in method_code:
            return {
                "is_real_bug": True,
                "confidence": 0.85,
                "reasoning": "Test checks array boundary conditions with exception assertions"
            }
        
        # Check for off-by-one patterns in loops
        off_by_one_loop = re.search(r'for\s*\([^;]*;\s*\w+\s*<=\s*\w+\.length', method_code)
        if off_by_one_loop and re.search(r'ArrayIndexOutOfBoundsException|IndexOutOfBoundsException', method_code):
            return {
                "is_real_bug": True,
                "confidence": 0.9,
                "reasoning": "Test identifies off-by-one error in loop with <= array.length condition"
            }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "array_index_error", 
                                            "This test may be checking for ArrayIndexOutOfBoundsException issues.")

    def _verify_operator_bug(self, method_code, method_info):
        """
        Verify operator bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Look for complex expressions with multiple operators
        complex_expr = re.search(r'assert.*?(?:&&|\|\|).*?(?:&&|\|\|)', method_code)
        
        if complex_expr:
            # Test contains complex boolean expressions
            
            # Check for parentheses in expressions
            has_parentheses = re.search(r'assert.*?\([^)]*(?:&&|\|\|)[^)]*\)', method_code)
            
            # Extract source context
            method_name = method_info.get("method_name", "unknown")
            source_context = self._extract_related_source_code(method_name)
            
            # Look for similar expressions in source
            if "&&" in source_context and "||" in source_context:
                # Source has complex expressions too
                if self._has_assertion_failure(method_info):
                    # Failed assertion with complex expressions likely indicates operator precedence issue
                    return {
                        "is_real_bug": True,
                        "confidence": 0.8,
                        "reasoning": "Complex expressions with mixed operators may have precedence bugs"
                    }
        
        # Check for bit operations vs logical operations
        if re.search(r'[^&]&[^&]', method_code) or re.search(r'[^|]\|[^|]', method_code):
            # Test uses bitwise operations, could be testing confusion between & and &&, | and ||
            
            # Extract source context
            method_name = method_info.get("method_name", "unknown")
            source_context = self._extract_related_source_code(method_name)
            
            if "&" in source_context and "&&" in source_context:
                # Source mixes bitwise and logical AND
                return {
                    "is_real_bug": True,
                    "confidence": 0.85,
                    "reasoning": "Code mixes bitwise (&) and logical (&&) operators, potential confusion"
                }
                
            if "|" in source_context and "||" in source_context:
                # Source mixes bitwise and logical OR
                return {
                    "is_real_bug": True,
                    "confidence": 0.85,
                    "reasoning": "Code mixes bitwise (|) and logical (||) operators, potential confusion"
                }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "operator_logic")
    
    def _verify_boolean_bug(self, method_code, method_info):
        """
        Verify boolean bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Check for boolean expression tests
        boolean_expr_test = re.search(r'assert(?:True|False)\s*\(([^;]+)\)', method_code)
        
        if boolean_expr_test:
            expr = boolean_expr_test.group(1)
            
            # Check for negation (!), likely testing boolean
            if "!" in expr:
                # Extract source context
                method_name = method_info.get("method_name", "unknown")
                source_context = self._extract_related_source_code(method_name)
                
                # Look for negation in source too
                if "!" in source_context and ("if" in source_context or "while" in source_context):
                    # Source uses negation in conditions
                    if self._has_assertion_failure(method_info):
                        # Failed assertion with negation likely indicates boolean bug
                        return {
                            "is_real_bug": True,
                            "confidence": 0.8,
                            "reasoning": "Boolean expression with negation fails"
                        }
        
        # Check for DeMorgan's law testing
        demorgan_test = re.search(r'assert.*?!\s*\([^)]*(?:&&|\|\|)[^)]*\)', method_code)
        
        if demorgan_test:
            # Test likely checking DeMorgan's law application
            if self._has_assertion_failure(method_info):
                # Failed assertion with DeMorgan pattern likely indicates boolean bug
                return {
                    "is_real_bug": True,
                    "confidence": 0.9,
                    "reasoning": "Negated AND/OR expression fails, possible DeMorgan's Law error"
                }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "boolean_bug")
    
    def _verify_infinite_loop(self, method_code, method_info):
        """
        Verify infinite loop bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Check for timeout assertions
        timeout_test = re.search(r'assertTimeout(?:Preemptively)?\s*\(', method_code)
        
        if timeout_test:
            # Test uses explicit timeout, likely testing against infinite loops
            
            # Check for assertion failures
            if self._has_assertion_failure(method_info):
                # Failed timeout assertion is strong evidence of infinite loop
                return {
                    "is_real_bug": True,
                    "confidence": 0.95,
                    "reasoning": "Timeout assertion failed, likely detected infinite loop"
                }
        
        # Check for long execution time
        if method_info.get("error", "").lower().find("timeout") >= 0 or \
           method_info.get("error", "").lower().find("timed out") >= 0:
            # Test timed out, likely hit an infinite loop
            return {
                "is_real_bug": True,
                "confidence": 0.9,
                "reasoning": "Test execution timed out, likely encountered infinite loop"
            }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "infinite_loop")
    
    def _verify_resource_leak(self, method_code, method_info):
        """
        Verify resource leak bug with specialized analysis
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Check for resource allocation patterns
        resource_allocation = re.search(r'new\s+(FileInputStream|FileOutputStream|BufferedReader|Scanner|Connection)', method_code)
        
        if resource_allocation:
            resource_type = resource_allocation.group(1)
            
            # Check for close method calls
            has_close = ".close()" in method_code
            
            # Check for try-with-resources
            has_try_resources = re.search(r'try\s*\([^)]*' + resource_type, method_code) is not None
            
            if not has_close and not has_try_resources:
                # Test doesn't close resources, might be testing resource leaks
                
                # Check source for close calls
                method_name = method_info.get("method_name", "unknown")
                source_context = self._extract_related_source_code(method_name)
                
                if ".close()" not in source_context and "try (" not in source_context:
                    # Source doesn't close resources either, likely a real bug
                    return {
                        "is_real_bug": True,
                        "confidence": 0.85,
                        "reasoning": f"Resource {resource_type} not closed in either test or source code"
                    }
        
        # Use LLM for deeper analysis
        return self._enhanced_llm_verification(method_code, method_info, "resource_leak")
    
    def _verify_resource_management(self, method_code, method_info):
        """
        Verify resource management issues like improper cleanup
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check if test attempts to verify resource cleanup
        has_resource_creation = re.search(r'new (File|InputStream|OutputStream|Reader|Writer|Connection)', method_code)
        
        # Check for typical resource management patterns in test
        has_close_verification = re.search(r'\.close\(\)|assertThrows.*\.close\(\)', method_code)
        has_try_with_resources = re.search(r'try\s*\([^)]*\)', method_code)
        has_leak_check = re.search(r'leak|closed|release', method_code, re.IGNORECASE)
        
        # Get source code context
        related_source = self._extract_related_source_code(method_name)
        
        # Check if the tested code uses resources properly
        uses_try_with_resources = re.search(r'try\s*\([^)]*\)', related_source)
        uses_finally_close = re.search(r'finally\s*\{[^}]*\.close\(\)', related_source)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_resource_creation and has_close_verification:
            confidence += 0.2
        if has_leak_check:
            confidence += 0.1
        if not (uses_try_with_resources or uses_finally_close):
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies resource management. "
                        f"{'Has resource creation check. ' if has_resource_creation else ''}"
                        f"{'Has close verification. ' if has_close_verification else ''}"
                        f"{'Uses try-with-resources in test. ' if has_try_with_resources else ''}"
                        f"{'Source does not use try-with-resources or finally blocks for cleanup. ' if not (uses_try_with_resources or uses_finally_close) else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "resource_management",
                "Analyze whether this test correctly identifies a resource management issue like a resource leak or improper cleanup."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_use_after_close(self, method_code, method_info):
        """
        Verify use-after-close issues
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check if test attempts to verify use after close
        has_resource_close = re.search(r'\.close\(\)', method_code)
        has_use_after_close = re.search(r'\.close\(\)[^;]*;\s*[^//].*\.\w+\(', method_code)
        has_assert_after_close = re.search(r'\.close\(\)[^;]*;\s*[^//].*assert', method_code, re.IGNORECASE)
        has_exception_check = re.search(r'assertThrows', method_code)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_resource_close and has_use_after_close:
            confidence += 0.2
        if has_assert_after_close:
            confidence += 0.1
        if has_exception_check:
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies use-after-close. "
                        f"{'Has resource close operation. ' if has_resource_close else ''}"
                        f"{'Attempts to use resource after closing. ' if has_use_after_close else ''}"
                        f"{'Has assertions after close. ' if has_assert_after_close else ''}"
                        f"{'Checks for exceptions when using closed resource. ' if has_exception_check else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "use_after_close",
                "Analyze whether this test correctly identifies a use-after-close issue where code attempts to use a resource after it's been closed."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_data_operation(self, method_code, method_info):
        """
        Verify data operation issues like improper conversions
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for type conversion tests
        has_type_conversion = re.search(r'\([a-zA-Z]+\)', method_code)
        has_numeric_literals = len(re.findall(r'\b\d+\b', method_code)) > 2  # Multiple numeric literals suggest testing numeric edge cases
        has_boundary_values = re.search(r'Integer\.MAX_VALUE|Integer\.MIN_VALUE|Long\.MAX_VALUE|Double\.MAX_VALUE', method_code)
        has_assertions = len(re.findall(r'assert', method_code, re.IGNORECASE)) > 0
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_type_conversion:
            confidence += 0.1
        if has_numeric_literals:
            confidence += 0.1
        if has_boundary_values:
            confidence += 0.2
        if has_assertions:
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies data operation correctness. "
                        f"{'Has type conversion operations. ' if has_type_conversion else ''}"
                        f"{'Uses multiple numeric literals. ' if has_numeric_literals else ''}"
                        f"{'Tests with boundary values. ' if has_boundary_values else ''}"
                        f"{'Contains assertions. ' if has_assertions else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "data_operation",
                "Analyze whether this test correctly identifies a data operation issue like improper type conversion or arithmetic problem."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_integer_truncation(self, method_code, method_info):
        """
        Verify integer truncation issues
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for specific truncation test patterns
        has_int_cast = re.search(r'\(int\)', method_code)
        has_long_values = re.search(r'\b\d+L\b', method_code) or re.search(r'Long\.\w+', method_code)
        has_large_number = re.search(r'\b\d{10,}\b', method_code)  # Numbers that might cause truncation
        has_assertion = re.search(r'assert\w+\s*\([^;]+\)', method_code)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_int_cast:
            confidence += 0.2
        if has_long_values:
            confidence += 0.2
        if has_large_number:
            confidence += 0.1
        if has_assertion:
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies integer truncation. "
                        f"{'Has int casting. ' if has_int_cast else ''}"
                        f"{'Uses long values. ' if has_long_values else ''}"
                        f"{'Contains large numbers. ' if has_large_number else ''}"
                        f"{'Has assertions. ' if has_assertion else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "integer_truncation",
                "Analyze whether this test correctly identifies an integer truncation issue where precision is lost in conversion."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_precision_loss(self, method_code, method_info):
        """
        Verify precision loss issues in floating point operations
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for specific precision loss patterns
        has_float_cast = re.search(r'\(float\)', method_code)
        has_double_values = re.search(r'\b\d+\.\d+[dD]?\b', method_code) or re.search(r'Double\.\w+', method_code)
        has_float_comparison = re.search(r'assertEquals\s*\([^,]+,\s*[^,]+,\s*\d+\.\d+[fF]?\)', method_code)
        has_delta = re.search(r'delta|epsilon|tolerance', method_code, re.IGNORECASE)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_float_cast:
            confidence += 0.2
        if has_double_values:
            confidence += 0.1
        if has_float_comparison:
            confidence += 0.2
        if has_delta:
            confidence += 0.2
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies precision loss. "
                        f"{'Has float casting. ' if has_float_cast else ''}"
                        f"{'Uses double values. ' if has_double_values else ''}"
                        f"{'Tests floating point comparison. ' if has_float_comparison else ''}"
                        f"{'Uses delta/epsilon tolerance in comparison. ' if has_delta else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "precision_loss",
                "Analyze whether this test correctly identifies a precision loss issue in floating point operations."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_exception_handling(self, method_code, method_info):
        """
        Verify exception handling issues
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for exception handling test patterns
        has_assert_throws = re.search(r'assertThrows', method_code)
        has_try_catch = re.search(r'try\s*\{[^}]*\}\s*catch\s*\(', method_code)
        has_expected_exception = re.search(r'@Test\s*\([^)]*expected\s*=', method_code)
        tests_exception_type = re.search(r'instanceof\s+\w+Exception|getClass\(\)', method_code)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_assert_throws:
            confidence += 0.2
        if has_try_catch:
            confidence += 0.1
        if has_expected_exception:
            confidence += 0.2
        if tests_exception_type:
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies exception handling. "
                        f"{'Uses assertThrows. ' if has_assert_throws else ''}"
                        f"{'Contains try-catch blocks. ' if has_try_catch else ''}"
                        f"{'Uses expected exception annotation. ' if has_expected_exception else ''}"
                        f"{'Tests exception type. ' if tests_exception_type else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "exception_handling",
                "Analyze whether this test correctly identifies an exception handling issue."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_swallowed_exception(self, method_code, method_info):
        """
        Verify swallowed exception issues
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for swallowed exception test patterns
        has_empty_catch = re.search(r'catch\s*\([^)]+\)\s*\{\s*\}', method_code)
        has_catch_throwable = re.search(r'catch\s*\(\s*(?:Exception|Throwable)\s+', method_code)
        has_verifications = re.search(r'verify|assert|fail', method_code, re.IGNORECASE)
        
        # Get source code context
        related_source = self._extract_related_source_code(method_name)
        
        # Check if the tested code has empty catch blocks
        source_has_empty_catch = re.search(r'catch\s*\([^)]+\)\s*\{\s*\}', related_source)
        source_catches_throwable = re.search(r'catch\s*\(\s*(?:Exception|Throwable)\s+[^{]*\{(?![^}]*(?:throw|log|printStackTrace))[^}]*\}', related_source)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_catch_throwable or has_empty_catch:
            confidence += 0.1
        if has_verifications:
            confidence += 0.1
        if source_has_empty_catch:
            confidence += 0.2
        if source_catches_throwable:
            confidence += 0.2
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies exception swallowing. "
                        f"{'Tests empty catch blocks. ' if has_empty_catch else ''}"
                        f"{'Tests catch Throwable/Exception. ' if has_catch_throwable else ''}"
                        f"{'Contains verifications. ' if has_verifications else ''}"
                        f"{'Source has empty catch blocks. ' if source_has_empty_catch else ''}"
                        f"{'Source catches Throwable without handling. ' if source_catches_throwable else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "swallowed_exception",
                "Analyze whether this test correctly identifies a swallowed exception issue where exceptions are caught but not properly handled."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_validation(self, method_code, method_info):
        """
        Verify input validation issues
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for validation test patterns
        has_null_test = re.search(r'null', method_code)
        has_empty_test = re.search(r'isEmpty\(\)|""\s*[,)]', method_code)
        has_assert_throws = re.search(r'assertThrows', method_code)
        has_validation_keywords = re.search(r'invalid|validate|illegal', method_code, re.IGNORECASE)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_null_test:
            confidence += 0.2
        if has_empty_test:
            confidence += 0.1
        if has_assert_throws:
            confidence += 0.1
        if has_validation_keywords:
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies input validation. "
                        f"{'Tests null values. ' if has_null_test else ''}"
                        f"{'Tests empty values. ' if has_empty_test else ''}"
                        f"{'Uses assertThrows. ' if has_assert_throws else ''}"
                        f"{'Contains validation keywords. ' if has_validation_keywords else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "validation",
                "Analyze whether this test correctly identifies an input validation issue."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_concurrency(self, method_code, method_info):
        """
        Verify concurrency issues
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for concurrency test patterns
        has_thread_creation = re.search(r'Thread|Runnable|ExecutorService', method_code)
        has_synchronization = re.search(r'synchronized|lock|Semaphore|CountDownLatch', method_code)
        has_concurrency_keywords = re.search(r'concurrent|parallel|race|deadlock', method_code, re.IGNORECASE)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_thread_creation:
            confidence += 0.2
        if has_synchronization:
            confidence += 0.2
        if has_concurrency_keywords:
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies concurrency issues. "
                        f"{'Creates threads. ' if has_thread_creation else ''}"
                        f"{'Uses synchronization. ' if has_synchronization else ''}"
                        f"{'Contains concurrency keywords. ' if has_concurrency_keywords else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "concurrency",
                "Analyze whether this test correctly identifies a concurrency issue like race condition or deadlock."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _verify_security(self, method_code, method_info):
        """
        Verify security vulnerabilities
        
        Parameters:
        method_code (str): Test method code
        method_info (dict): Method information
        
        Returns:
        dict: Verification result
        """
        # Extract method name for better context
        method_name = method_info.get("method_name", "unknown")
        
        # Check for security test patterns
        has_sql_testing = re.search(r'SQL|executeQuery|prepareStatement', method_code, re.IGNORECASE)
        has_credential_testing = re.search(r'password|secret|key|auth', method_code, re.IGNORECASE)
        has_security_keywords = re.search(r'security|vulnerability|injection|exploit', method_code, re.IGNORECASE)
        
        # Determine confidence based on evidence
        confidence = 0.5
        if has_sql_testing:
            confidence += 0.2
        if has_credential_testing:
            confidence += 0.2
        if has_security_keywords:
            confidence += 0.1
            
        # Create verification result
        result = {
            "is_real_bug": confidence >= 0.7,
            "confidence": confidence,
            "reasoning": f"Test {method_name} verifies security vulnerabilities. "
                        f"{'Tests SQL operations. ' if has_sql_testing else ''}"
                        f"{'Tests credential handling. ' if has_credential_testing else ''}"
                        f"{'Contains security keywords. ' if has_security_keywords else ''}"
        }
        
        # For low confidence, use enhanced LLM verification
        if 0.4 < confidence < 0.8:
            llm_result = self._enhanced_llm_verification(
                method_code, 
                method_info, 
                "security",
                "Analyze whether this test correctly identifies a security vulnerability."
            )
            
            # Merge with LLM result
            result["llm_verified"] = True
            result["is_real_bug"] = llm_result.get("is_real_bug", result["is_real_bug"])
            result["confidence"] = max(confidence, llm_result.get("confidence", 0.0))
            result["reasoning"] += f" LLM: {llm_result.get('reasoning', 'No additional reasoning')}"
        
        return result
    
    def _enhanced_llm_verification(self, method_code, method_info, bug_type, additional_context=""):
        """
        Verify bug using enhanced LLM-based analysis with failure-specific prompt
        
        Parameters:
        method_code (str): Method code to verify
        method_info (dict): Method information
        bug_type (str): Type of bug to verify
        additional_context (str): Additional context for the verification
        
        Returns:
        dict: Verification result
        """
        # 添加针对数组索引错误的专门提示
        array_index_specific_prompt = ""
        if bug_type == "array_index_error":
            array_index_specific_prompt = """
    Pay special attention to array indices and boundary checks in this code.
    When analyzing array access patterns, look for:
    1. Proper validation against array.length
    2. Off-by-one errors in loops (using <= vs <)
    3. Edge cases (empty arrays, accessing last element)
    4. Multi-dimensional array access
    5. Negative indices or indices beyond array bounds
    6. Patterns suggesting IndexOutOfBoundsException or ArrayIndexOutOfBoundsException
    """
        
        # 添加针对字符串索引错误的专门提示
        string_index_specific_prompt = ""
        if bug_type == "string_index_error":
            string_index_specific_prompt = """
    Pay special attention to string indices and length checks in this code.
    When analyzing string operations, look for:
    1. Proper validation of indices against string.length()
    2. charAt() or substring() operations without boundary checks
    3. Edge cases (empty strings, accessing last character)
    4. Negative indices or indices beyond string length
    5. Patterns suggesting StringIndexOutOfBoundsException
    """
        
        # Prepare specialized prompt for bug verification
        prompt = f"""
    You are an expert in Java bug verification with specialized knowledge of bugs. 
    I will provide you with a Java test method and source code context for a class.
    The test method potentially reveals a logical bug of type: {bug_type}.

    Test method:
    ```java
    {method_code}
    ```

    Source code context:
    ```java
    {self._extract_related_source_code(method_info.get("method_name", "unknown"))}
    ```

    Error information:
    {method_info.get("error", "No specific error information")}

    {additional_context}

    {array_index_specific_prompt}
    {string_index_specific_prompt}

    Please analyze whether this test method reveals a real bug in the source code.
    Bugs include boundary errors, operator precedence issues, boolean logic mistakes,
    off-by-one errors, null handling problems, array index errors, string index errors and other bugs.

    For a bug to be considered real:
    1. It must reveal a true flaw in the source code's logic, not just a testing error
    2. The behavior must violate the expected contract or reasonable assumptions
    3. It must not merely be a test that expects different behavior than intended

    Give your verdict in this format:
    1. VERDICT: "REAL BUG" or "FALSE POSITIVE"
    2. CONFIDENCE: A number between 1-10
    3. REASONING: Your detailed analysis of the bug

    Focus specifically on the properties of the code and how the test reveals bugs.
    """
        try:
            # Call the LLM API
            api_response = call_anthropic_api(prompt)
            # api_response = call_deepseek_api(prompt)
            
            if not api_response or len(api_response) < 50:
                # Fall back to standard verification
                return self._standard_verification(method_code, method_info)
                
            # Parse the response
            verdict_match = re.search(r'VERDICT:\s*["\'"]?(REAL LOGICAL BUG|FALSE POSITIVE)["\'"]?', api_response, re.IGNORECASE)
            confidence_match = re.search(r'CONFIDENCE:\s*(\d+(?:\.\d+)?)', api_response)
            reasoning_match = re.search(r'REASONING:(.+?)(?=VERDICT:|CONFIDENCE:|\Z)', api_response, re.DOTALL)
            
            if not verdict_match:
                # Look for explicit statements about bug status
                if re.search(r'(this|it)\s+(is|appears to be)\s+a\s+real\s+(?:logical)?\s*bug', api_response.lower()) or \
                "real logical bug" in api_response.lower():
                    is_real_bug = True
                    verification_confidence = 0.8
                elif re.search(r'(this|it)\s+(is|appears to be)\s+not\s+a\s+real\s+(?:logical)?\s*bug', api_response.lower()) or \
                    "false positive" in api_response.lower():
                    is_real_bug = False
                    verification_confidence = 0.8
                else:
                    # Count signals for more nuanced decision
                    positive_signals = ["real logical bug", "logical flaw", "logic error", "boundary error", 
                                    "off-by-one", "operator precedence", "boolean error", "array index", "string index"]
                    negative_signals = ["test expectation", "not a bug", "false positive", "unlikely to be real",
                                    "test issue", "misunderstanding", "as designed"]
                    
                    pos_count = sum(1 for signal in positive_signals if signal in api_response.lower())
                    neg_count = sum(1 for signal in negative_signals if signal in api_response.lower())
                    
                    if pos_count > neg_count:
                        is_real_bug = True
                        verification_confidence = 0.6 + min(0.3, 0.05 * (pos_count - neg_count))
                    else:
                        is_real_bug = False
                        verification_confidence = 0.6 + min(0.3, 0.05 * (neg_count - pos_count))
                
                # Try to extract reasoning from the response
                reasoning = api_response[:500]  # Just use the first part of the response
            else:
                # Process structured response
                is_real_bug = verdict_match.group(1).upper() == "REAL LOGICAL BUG"
                
                # Get confidence score
                if confidence_match:
                    llm_confidence = float(confidence_match.group(1))
                    verification_confidence = min(llm_confidence / 10, 0.95)  # Convert to 0-1 scale
                else:
                    verification_confidence = 0.7 if is_real_bug else 0.7  # Default confidence
                
                # Get reasoning
                if reasoning_match:
                    reasoning = reasoning_match.group(1).strip()
                else:
                    # Try to extract reasoning from the full response
                    reasoning = api_response[:500]  # Limit length
            
            return {
                "is_real_bug": is_real_bug,
                "confidence": verification_confidence,
                "reasoning": reasoning[:500] if reasoning else "No detailed reasoning provided",
                "bug_type": bug_type
            }
            
        except Exception as e:
            logger.error(f"Error in enhanced LLM verification: {str(e)}")
            # Fall back to standard verification
            return self._standard_verification(method_code, method_info)
    
    def _extract_assertion_context(self, method_code):
        """
        Extract context around assertions in a method
        
        Parameters:
        method_code (str): Method code
        
        Returns:
        str: Context around assertions
        """
        assertion_match = re.search(r'assert\w+\s*\(([^;]+)\)', method_code)
        if assertion_match:
            return assertion_match.group(1)
        return ""
    
    def _extract_related_source_code(self, method_name, context_lines=10):
        """
        Extract related source code for a test method
        
        Parameters:
        method_name (str): Test method name
        context_lines (int): Number of context lines
        
        Returns:
        str: Related source code
        """
        # Clean up method name (remove "test" prefix and other test-specific parts)
        cleaned_name = method_name.lower()
        if cleaned_name.startswith("test"):
            cleaned_name = cleaned_name[4:]
        
        # Look for methods in source code that might be related
        method_pattern = r'(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?(?:\w+(?:<[^>]+>)?)\s+(\w+)\s*\([^)]*\)\s*\{'
        methods = re.finditer(method_pattern, self.source_code)
        
        best_match = None
        best_score = 0
        
        for match in methods:
            source_method = match.group(1).lower()
            # Calculate similarity score
            score = self._similarity_score(cleaned_name, source_method)
            if score > best_score:
                best_score = score
                best_match = match
        
        if best_match and best_score > 0.3:  # Threshold for meaningful similarity
            # Extract method body with context
            method_start = best_match.start()
            
            # Find method end
            brace_count = 0
            method_end = method_start
            
            for i in range(method_start, len(self.source_code)):
                if self.source_code[i] == '{':
                    brace_count += 1
                elif self.source_code[i] == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        method_end = i + 1
                        break
            
            # Extract method with context
            context_start = max(0, method_start - context_lines * 80)  # Approximate line length
            context_end = min(len(self.source_code), method_end + context_lines * 80)
            
            return self.source_code[context_start:context_end]
        
        # If no good match found, return a section of the source code
        source_lines = self.source_code.split('\n')
        middle = len(source_lines) // 2
        start = max(0, middle - context_lines)
        end = min(len(source_lines), middle + context_lines)
        
        return '\n'.join(source_lines[start:end])
    
    def _similarity_score(self, name1, name2):
        """
        Calculate similarity score between two method names
        
        Parameters:
        name1 (str): First method name
        name2 (str): Second method name
        
        Returns:
        float: Similarity score (0-1)
        """
        # Basic exact substring check
        if name1 in name2 or name2 in name1:
            return 0.8
        
        # Check common substrings (camelCase split)
        words1 = re.findall(r'[A-Z]?[a-z]+', name1)
        words2 = re.findall(r'[A-Z]?[a-z]+', name2)
        
        if not words1 or not words2:
            return 0
        
        # Count matching words
        matches = sum(1 for w1 in words1 if any(w1 in w2 for w2 in words2))
        return matches / max(len(words1), len(words2))
    
    def _has_assertion_failure(self, method_info):
        """
        Check if the method has assertion failures
        
        Parameters:
        method_info (dict): Method information
        
        Returns:
        bool: True if the method has assertion failures
        """
        error = method_info.get("error", "")
        return "AssertionError" in error or "expected:" in error or "but was:" in error
