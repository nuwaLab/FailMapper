#!/usr/bin/env python3
"""
Bug Verification Module

This module handles verification of potential bugs found during test execution
by using LLMs to analyze whether they are real issues or false positives.
"""

import logging
import re
import traceback
import time
from feedback import call_anthropic_api, call_gpt_api, call_deepseek_api, reset_llm_metrics, get_llm_metrics_summary

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("bug_verification")

def verify_bug_with_llm(bug_info, test_method, source_code, class_name):
    """
    Use LLM to verify if a detected bug is a legitimate bug or a false positive
    
    Parameters:
    bug_info (dict): Information about the detected bug
    test_method (str): The test method code that triggered the bug
    source_code (str): The source code of the class being tested
    class_name (str): Name of the class being tested
    
    Returns:
    dict: Verification result with additional reasoning
    """
    # Defensive checks for inputs
    if not test_method or not source_code:
        logger.warning("Missing test method or source code for bug verification")
        return {
            "is_real_bug": bug_info.get("confidence", 0.5) > 0.7,  # Default based on confidence
            "confidence": bug_info.get("confidence", 0.5),
            "reasoning": "Insufficient data for verification"
        }
    
    bug_type = bug_info.get("type", "unknown")
    error_message = bug_info.get("error", "")
    severity = bug_info.get("severity", "medium")
    confidence = bug_info.get("confidence", 0.5)
    
    # Pre-filter common false positives without LLM call
    # Check for known patterns in specific bug types
    if bug_type == "assertion_failure":
        # Check if this is just an incorrect assertion in empty/null/trivial tests
        if ("expected: <null>" in error_message and "but was: <" in error_message) or \
           ("expected: <[]>" in error_message and "but was: <" in error_message) or \
           ("expected: <>" in error_message and "but was: <" in error_message):
            if "null" in test_method.lower() or "empty" in test_method.lower():
                return {
                    "is_real_bug": False,
                    "confidence": 0.9,
                    "reasoning": "This is a common false positive for empty/null tests - the test expectation is likely incorrect"
                }
    
    # Auto-verify high-confidence memory errors
    if bug_type == "memory_error" or "OutOfMemoryError" in error_message or "StackOverflowError" in error_message:
        return {
            "is_real_bug": True,
            "confidence": 0.95,
            "reasoning": "Memory errors are almost always real bugs, typically indicating infinite recursion or excessive memory allocation"
        }
        
    # If confidence is already very high, skip verification
    if confidence > 0.9:
        logger.info(f"Skipping verification for high-confidence bug: {bug_type}")
        return {
            "is_real_bug": True,
            "confidence": confidence,
            "reasoning": "High confidence pre-verification"
        }
    
    # Truncate source code if too long to fit in context window
    # trimmed_source = source_code[:5000] if len(source_code) > 5000 else source_code
    # if len(source_code) > 5000:
    #     logger.info(f"Truncated source code from {len(source_code)} to 5000 chars for LLM verification")
    
    # Prepare analysis prompt
    prompt = f"""
You are a professional Java analysis expert specializing in identifying real bugs and false positives in unit tests.
I will provide you with the source code of a Java class, a test method, and information about a potential bug found during testing.
Please analyze whether this is a real bug in the source code or just a false positive caused by testing environment or code issues.

Class name: {class_name}

Source code:
```java
{source_code}
```

Test method:
```java
{test_method}
```

Issue found:
- Bug type: {bug_type}
- Severity: {severity}
- Error message: {error_message}

Please analyze whether the issue found by this test method is a real bug in the source code or a false positive due to test code issues or environment problems.

Please provide your response in this specific format:
1. VERDICT: "REAL BUG" or "FALSE POSITIVE"
2. CONFIDENCE: A number between 1-10
3. REASONING: Your detailed analysis and reasoning

The analysis should particularly consider:
1. Whether the error is caused by the test code itself (e.g., test environment configuration, test dependencies, etc.)
2. Whether the issue actually exposes a defect in the class being tested
3. Whether the test method is reasonable or if it's testing unreasonable/extreme edge cases
4. Whether the test expectations match the intended behavior of the class

For CONFIDENCE score, use these guidelines:
- 9-10: Very confident in the assessment
- 7-8: Confident but with some uncertainty
- 5-6: Moderately confident
- 1-4: Significant uncertainty
"""
    
    try:
        # Attempt to use Anthropic first for verification
        logger.info(f"Verifying potential {bug_type} bug in class {class_name}")
        response = call_anthropic_api(prompt)
        # response = call_deepseek_api(prompt)
        
        if not response or len(response) < 50:
            logger.warning(f"Insufficient response from API for bug verification: {response}")
            return {
                "is_real_bug": confidence > 0.7,  # Default to pre-verification confidence
                "confidence": confidence,
                "reasoning": "Verification failed - insufficient API response"
            }
            
    except Exception as e:
        logger.warning(f"Failed to call Anthropic API, falling back to GPT: {str(e)}")
        try:
            # Fall back to GPT
            response = call_gpt_api(prompt)
            if not response or len(response) < 50:
                raise ValueError("Insufficient response")
        except Exception as e2:
            logger.error(f"Failed to verify bug with LLM: {str(e2)}")
            # If both fail, rely on the existing confidence score
            return {
                "is_real_bug": confidence > 0.7,
                "confidence": confidence,
                "reasoning": "Unable to perform LLM verification"
            }
    # print("--------------------------------")    
    # print("response:")
    # print(response)
    # print("--------------------------------")
    # Parse the response
    verdict_match = re.search(r'VERDICT:\s*["\'"]?(REAL BUG|FALSE POSITIVE)["\'"]?', response, re.IGNORECASE)
    confidence_match = re.search(r'CONFIDENCE:\s*(\d+(?:\.\d+)?)', response)
    reasoning_match = re.search(r'REASONING:(.+?)(?=VERDICT:|CONFIDENCE:|\Z)', response, re.DOTALL)
    
    # If no structured response was found, use a more flexible pattern matching
    if not verdict_match:
        # Look for explicit statements about bug status
        if re.search(r'(this|it)\s+(is|appears to be)\s+a\s+real\s+bug', response.lower()) or \
           "yes, this is a real bug" in response.lower() or \
           "real bug" in response.lower():
            is_real_bug = True
            verification_confidence = 0.8
        elif re.search(r'(this|it)\s+(is|appears to be)\s+not\s+a\s+real\s+bug', response.lower()) or \
             "not a real bug" in response.lower() or \
             "false positive" in response.lower():
            is_real_bug = False
            verification_confidence = 0.8
        else:
            # Count signals for more nuanced decision
            positive_signals = ["real issue", "actual bug", "code defect", "exposes a problem", 
                               "defect in the class", "vulnerability", "should be fixed"]
            negative_signals = ["unreasonable test", "test method issue", "test environment problem", 
                               "not a bug", "expected behavior", "by design", "unreasonable expectation",
                               "edge case that", "not realistic", "documented limitation"]
            
            pos_count = sum(1 for signal in positive_signals if signal in response.lower())
            neg_count = sum(1 for signal in negative_signals if signal in response.lower())
            
            if pos_count > neg_count:
                is_real_bug = True
                verification_confidence = 0.6 + min(0.3, 0.05 * (pos_count - neg_count))
            else:
                is_real_bug = False
                verification_confidence = 0.6 + min(0.3, 0.05 * (neg_count - pos_count))
        
        # Try to extract reasoning from the response
        reasoning = response[:500]  # Just use the first part of the response
    else:
        # Process structured response
        is_real_bug = verdict_match.group(1).upper() == "REAL BUG"
        
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
            reasoning = response[:500]  # Limit length
    
    # Log the verification result
    result_type = "REAL BUG" if is_real_bug else "FALSE POSITIVE"
    logger.info(f"Bug verification result: {result_type} with confidence {verification_confidence:.2f}")
    
    return {
        "is_real_bug": is_real_bug,
        "confidence": verification_confidence,
        "reasoning": reasoning[:500] if reasoning else "No detailed reasoning provided",
        "full_response": response[:1000]  # Store truncated response for debugging
    }

def filter_verified_bug_methods(bug_methods, source_code, class_name, package_name, test_code=None):
    """
    Use LLM to verify which bug-finding methods are likely to be real bugs in batch
    
    Parameters:
    bug_methods (list): List of potential bug-finding methods
    source_code (str): Source code of the class
    class_name (str): Class name
    package_name (str): Package name
    test_code (str): Optional full test code for context
    
    Returns:
    list: Filtered list of verified bug methods with verification results
    """
    verified_methods = []
    
    try:
        logger.info(f"Batch filtering {len(bug_methods)} potential bug methods...")
        
        if not bug_methods:
            return []
            
        # Check for methods that were already verified and skip them
        methods_to_verify = []
        for method in bug_methods:
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
                    verified_methods.append(method)
                    filtered_methods.remove(method)  # Remove from methods to verify
                    continue
                    
                # Check for other common false positive patterns
                if "Expected exception to be thrown" in method_code:
                    method["verified"] = True
                    method["is_real_bug"] = False
                    method["verification_confidence"] = 0.9
                    method["verification_reasoning"] = "Test expects exception that is not thrown - likely due to changed behavior."
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

Source class: {package_name}.{class_name}

Source code snippet:
```java
{source_code[:2500]}
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
                result = call_anthropic_api(prompt, max_tokens=8192)
                # result = call_deepseek_api(prompt)
                
                if not result or len(result) < 100:
                    logger.warning("Insufficient response from LLM for batch bug verification")
                    # Process remaining methods in batch as likely false positives
                    for method in batch:
                        if isinstance(method, dict):
                            method["verified"] = True
                            method["is_real_bug"] = False
                            method["verification_confidence"] = 0.7
                            method["verification_reasoning"] = "Automated assessment: likely false positive due to insufficient LLM response"
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
                        verified_methods.append({
                            "code": method,
                            "verified": True,
                            "is_real_bug": idx in verified_indices,
                            "verification_confidence": confidence_scores.get(idx, 0.5),
                            "bug_info": []
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
                        verified_methods.append(method)
        
        # Tally up verified bugs vs false positives
        verified_real_bugs = len([m for m in verified_methods if m.get("is_real_bug", False)])
        verified_false_positives = len([m for m in verified_methods if m.get("verified", False) and not m.get("is_real_bug", False)])
        
        logger.info(f"Verified {len(verified_methods)} methods: {verified_real_bugs} real bugs, {verified_false_positives} false positives")
        return verified_methods
            
    except Exception as e:
        logger.error(f"Failed to filter bug methods: {str(e)}")
        logger.error(traceback.format_exc())
        return verified_methods

def merge_verified_bug_tests(base_test, verified_bug_methods, class_name, package_name, project_dir, source_code):
    """
    Merge verified bug-finding test methods into a base test
    
    Parameters:
    base_test (str): Base test code
    verified_bug_methods (list): List of verified bug-finding methods
    class_name (str): Class name
    package_name (str): Package name
    project_dir (str): Project directory
    source_code (str): Source code
    
    Returns:
    tuple: (enhanced_test, bug_info_dict)
    """
    # 导入traceback以确保可用（避免之前的错误）
    import traceback
    
    if base_test is None:
        logger.error("Base test is None, cannot merge")
        return None, {"error": "Base test is None"}
    
    if not verified_bug_methods:
        logger.info("No verified bug methods to merge")
        return base_test, {"merged_methods": 0}
    
    try:
        # 过滤，只保留真正的bug方法
        real_bug_methods = [m for m in verified_bug_methods 
                           if isinstance(m, dict) and m.get("is_real_bug", True)]
        
        if not real_bug_methods:
            logger.info("No real bug methods to merge after filtering")
            return base_test, {"merged_methods": 0, "message": "No real bugs after filtering"}
            
        logger.info(f"Merging {len(real_bug_methods)} verified real bug methods into base test")
        
        # 首先，检查方法是否已存在于基础测试中
        methods_to_merge = []
        
        for method in real_bug_methods:
            method_code = method.get("code", "")
            if not method_code:
                continue
                
            # 提取方法名和签名
            name_match = re.search(r'void\s+(\w+)\s*\(', method_code)
            if not name_match:
                continue
                
            method_name = name_match.group(1)
            
            # 尝试提取完整签名（包括参数列表）
            full_signature_match = re.search(r'void\s+(\w+\s*\([^)]*\))', method_code)
            method_signature = full_signature_match.group(1).strip() if full_signature_match else method_name + "()"
            
            # 1. 检查方法签名是否已存在
            if re.search(r'void\s+' + re.escape(method_signature), base_test):
                logger.info(f"Method with signature '{method_signature}' already exists in base test, skipping")
                continue
                
            # 2. 检查方法名是否已存在 - 这将捕获不同参数但相同名称的重载方法
            method_name_pattern = r'void\s+' + re.escape(method_name) + r'\s*\('
            if re.search(method_name_pattern, base_test):
                # 方法名已存在，但签名不同（可能是重载） - 重命名以避免冲突
                suffix = 1
                while re.search(r'void\s+' + re.escape(f"{method_name}_{suffix}") + r'\s*\(', base_test):
                    suffix += 1
                
                # 创建新方法名    
                new_name = f"{method_name}_{suffix}"
                logger.info(f"Renaming method from '{method_name}' to '{new_name}' to avoid conflict")
                
                # 替换方法名
                method_code = re.sub(
                    r'(public\s+|private\s+|protected\s+)?void\s+' + re.escape(method_name) + r'\s*\(',
                    r'\1void ' + new_name + r'(',
                    method_code
                )
                
                # 更新方法中对自身名称的任何引用
                method_code = method_code.replace(f"Method {method_name}", f"Method {new_name}")
                method_code = method_code.replace(f"Test {method_name}", f"Test {new_name}")
                
                method_name = new_name
            
            # 3. 检查是否存在具有相同内容的方法（忽略空格和注释）
            # 清理代码以便于比较
            cleaned_method_body = re.sub(r'@Test.*?void\s+\w+\s*\([^{]*\{', '', method_code, flags=re.DOTALL)
            cleaned_method_body = re.sub(r'//.*?$', '', cleaned_method_body, flags=re.MULTILINE)
            cleaned_method_body = re.sub(r'/\*.*?\*/', '', cleaned_method_body, flags=re.DOTALL)
            cleaned_method_body = re.sub(r'\s+', ' ', cleaned_method_body).strip()
            
            # 创建一个足够独特的片段以检查其是否存在
            if len(cleaned_method_body) > 40:
                key_snippet = cleaned_method_body[:40]  # 使用前40个字符作为指纹
                if key_snippet in base_test:
                    logger.info(f"Method body appears similar to existing code, skipping")
                    continue
            
            # 通过所有检查，可以添加到待合并列表
            method["name"] = method_name  # 更新方法名（以防已重命名）
            method["code"] = method_code  # 更新代码（以防已修改）
            methods_to_merge.append(method)
        
        # 如果没有方法要合并，返回原始测试
        if not methods_to_merge:
            logger.info("No methods to merge after duplication checks")
            return base_test, {"merged_methods": 0}
        
        # 找到类结束位置进行插入
        class_end = base_test.rfind('}')
        if class_end <= 0:
            logger.error("Could not find class end in base test")
            return base_test, {"error": "Could not find class end"}
            
        # 构建增强的测试代码
        enhanced_test = base_test[:class_end]
        
        # 添加bug验证注释和方法
        for method in methods_to_merge:
            # 添加bug验证注释
            bug_type = method.get("bug_type", "unknown")
            verification = method.get("verification_confidence", 0.8)
            severity = method.get("severity", "medium")
            
            method_code = method["code"]
            # 确保方法有适当的缩进
            method_code = "\n    " + method_code.replace("\n", "\n    ") 
            
            # 在方法开头添加验证注释
            if not "// Verified real bug" in method_code:
                method_code = method_code.replace("@Test", "@Test\n    // Verified real bug test: " +
                                                 f"Type: {bug_type}, Severity: {severity}, " +
                                                 f"Confidence: {verification:.2f}")
            
            enhanced_test += method_code + "\n"
        
        # 添加类结束括号
        enhanced_test += "\n}" if not enhanced_test.rstrip().endswith("}") else ""
        
        # 创建bug信息字典
        bug_info = {
            "merged_methods": len(methods_to_merge),
            "method_names": [m.get("name", "unknown") for m in methods_to_merge],
            "real_bugs": True
        }
        
        logger.info(f"Successfully merged {len(methods_to_merge)} verified bug methods")
        return enhanced_test, bug_info
        
    except Exception as e:
        logger.error(f"Error merging bug methods: {str(e)}")
        logger.error(traceback.format_exc())
        return base_test, {"error": str(e)}

def attempt_to_fix_test_expectations(method_code, reasoning):
    """
    Attempt to fix test expectations that don't match the actual behavior
    
    Parameters:
    method_code (str): The test method code
    reasoning (str): Verification reasoning from the LLM
    
    Returns:
    str or None: Fixed method code or None if couldn't fix
    """
    # Extract method name for better logging
    name_match = re.search(r'void\s+(\w+)\s*\(', method_code)
    method_name = name_match.group(1) if name_match else "unknown"
    
    # Fix for URL special characters test
    if ("testCreateURLWithSpecialCharacters" in method_code or 
        "URLSpecialChars" in method_code) and "assertThrows" in method_code and "ParseException" in method_code:
        logger.info(f"Fixing URL special characters test: {method_name}")
        # URL class actually accepts special characters, so test should expect success
        fixed = re.sub(
            r'assertThrows\(\s*ParseException\.class,\s*\(\)\s*->\s*\{?\s*(?:URL\s+)?\w+\s*=\s*TypeHandler\.createURL\("([^"]+)"\)\s*;?\s*\}?\s*\)',
            r'URL url = TypeHandler.createURL("\1");\n        assertNotNull(url);\n        // Test originally expected ParseException, but URLs accept these special characters',
            method_code
        )
        return fixed
    
    # Fix for BigDecimal empty string test
    if ("testCreateValueWithEmptyBigDecimal" in method_code or 
        "EmptyBigDecimal" in method_code) and "NumberFormatException" in method_code:
        logger.info(f"Fixing BigDecimal empty string test: {method_name}")
        # TypeHandler wraps NumberFormatException in ParseException
        fixed = method_code.replace(
            "NumberFormatException.class", 
            "ParseException.class"
        )
        return fixed
    
    # Fix for large decimal number test
    if ("testCreateNumberWithLargeDecimal" in method_code or 
        "LargeDecimal" in method_code) and "throws ParseException" in method_code:
        logger.info(f"Fixing large decimal test: {method_name}")
        # Method might throw NumberFormatException for very large numbers
        return re.sub(
            r'(void\s+\w+\s*\([^)]*\))\s*throws\s+ParseException\s*\{',
            r'\1 {\n        assertThrows(ParseException.class, () -> {',
            method_code
        ).replace(
            "assertEquals(new BigInteger", 
            "});\n        /* Original expectation was incorrect:\n        assertEquals(new BigInteger"
        ).replace(
            ");", 
            ");*/"
        )
    
    # Generic fix for assertions that consistently fail in the same way
    if "expected:" in method_code and "but was:" in method_code and reasoning:
        # Try to find what value it expects vs what it gets
        expected_actual_pattern = r"expected:.*?<([^>]+)>.*?but was:.*?<([^>]+)>"
        match = re.search(expected_actual_pattern, method_code)
        
        if match:
            expected = match.group(1)
            actual = match.group(2)
            
            # If the reasoning suggests the actual behavior is correct
            if "actual behavior is correct" in reasoning.lower() or "expected value is incorrect" in reasoning.lower():
                logger.info(f"Fixing assertion in {method_name}: actual value {actual} appears to be correct")
                
                # Find the assertion line
                assertion_pattern = r'(assert\w+\([^;]*expected:.*?<' + re.escape(expected) + r'>.*?but was:.*?<' + re.escape(actual) + r'>[^;]*;)'
                assertion_match = re.search(assertion_pattern, method_code)
                
                if assertion_match:
                    assertion_line = assertion_match.group(1)
                    # Replace the assertion with a correct one
                    fixed_assertion = f"// Original assertion failed: {assertion_line}\n        assertEquals({actual}, "
                    
                    # Extract what we're checking
                    method_calls = re.findall(r'(\w+\([^)]*\))', assertion_line)
                    if method_calls:
                        fixed_assertion += f"{method_calls[-1]});"
                    else:
                        # Fall back to a basic assertion
                        fixed_assertion += f"actual);"
                    
                    fixed_code = method_code.replace(assertion_line, fixed_assertion)
                    return fixed_code
    
    # Couldn't find a way to fix this method
    logger.info(f"Could not automatically fix expectations for method: {method_name}")
    return None