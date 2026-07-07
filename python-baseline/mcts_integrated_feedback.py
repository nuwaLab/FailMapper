#!/usr/bin/env python3
"""
Enhanced MCTS Integrated Feedback System for Java Unit Test Generation

This script provides a command-line interface for the Enhanced MCTS Test Generator,
which combines Monte Carlo Tree Search with LLMs to generate high-quality Java unit tests
with improved code coverage and bug detection capabilities.

The system focuses on reducing false positives by:
1. Using confidence scores for bug detection
2. Providing immediate or batch verification of potential bugs
3. Only merging verified bug-finding tests into the final output

Usage:
  python mcts_integrated_feedback.py --project /path/to/project --prompt /path/to/prompts --class ClassName --package org.example

Options:
  --verify-mode [immediate|batch|none] : Controls when bug verification occurs
  --prioritize-bugs : Gives higher priority to bug detection over coverage
  --max-iterations : Number of MCTS iterations to perform
  --batch : Process all classes in the prompt directory
"""

import os
import sys
import json
import time
import logging
import argparse
import traceback
from collections import defaultdict

# Import core components
from enhanced_test_state import TestState
from verify_bug_with_llm import (
    verify_bug_with_llm, 
    filter_verified_bug_methods, 
    merge_verified_bug_tests
)
from enhanced_mcts_test_generator import (
    EnhancedMCTSTestGenerator,
    TestValidator,
    TestMethodExtractor,
    improve_test_coverage_with_enhanced_mcts,
    handle_false_positive_tests
)

# Import from feedback module
from feedback import (
    generate_initial_test, save_test_code, generate_test_summary,
    read_source_code, find_source_code, strip_java_comments,
    run_tests_with_jacoco, get_coverage_percentage, 
    check_pom_for_jacoco, add_jacoco_to_pom,
    read_test_prompt_file, create_consolidated_report,
    call_anthropic_api, call_gpt_api, extract_java_code,
    reset_llm_metrics, get_llm_metrics_summary, log_detailed_metrics
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("mcts_integrated_feedback.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("mcts_integrated_feedback")


def analyze_source_code(source_code, class_name):
    """
    Perform detailed analysis of source code to improve test generation
    
    Parameters:
    source_code (str): Source code content
    class_name (str): Class name
    
    Returns:
    dict: Analysis results
    """
    import re
    
    if not source_code:
        return {}
        
    analysis = {
        "class_name": class_name,
        "methods": [],
        "interfaces": [],
        "superclass": None,
        "fields": [],
        "is_abstract": False,
        "modifiers": [],
        "line_count": len(source_code.split('\n'))
    }
    
    # Check if class is abstract
    abstract_pattern = r'abstract\s+class\s+' + re.escape(class_name)
    if re.search(abstract_pattern, source_code):
        analysis["is_abstract"] = True
        analysis["modifiers"].append("abstract")
    
    # Extract class modifiers
    modifier_pattern = r'(public|protected|private|final|static)\s+(?:abstract\s+)?class\s+' + re.escape(class_name)
    modifier_match = re.search(modifier_pattern, source_code)
    if modifier_match:
        analysis["modifiers"].append(modifier_match.group(1))
    
    # Extract methods with visibility and return types
    method_pattern = r'(public|protected|private)?\s+(?:static\s+)?(?:final\s+)?(\w+(?:<[^>]+>)?)\s+(\w+)\s*\(([^)]*)\)'
    method_matches = re.finditer(method_pattern, source_code)
    
    for match in method_matches:
        visibility = match.group(1) or "package-private"
        return_type = match.group(2)
        method_name = match.group(3)
        params = match.group(4)
        
        # Skip internal JVM methods
        if method_name in ("hashCode", "equals", "toString", "clone", "finalize") and not params.strip():
            continue
            
        # Skip constructor with class name
        if method_name == class_name:
            continue
            
        analysis["methods"].append({
            "name": method_name,
            "visibility": visibility,
            "return_type": return_type,
            "params": params.strip()
        })
    
    # Extract implemented interfaces
    interface_pattern = r'implements\s+([\w\s,.<>]+)(?:\{|\s+\{)'
    interface_match = re.search(interface_pattern, source_code)
    if interface_match:
        interfaces = [i.strip() for i in interface_match.group(1).split(',')]
        analysis["interfaces"] = interfaces
    
    # Extract superclass
    extends_pattern = r'extends\s+([\w.<>]+)(?:\s+implements|\s*\{)'
    extends_match = re.search(extends_pattern, source_code)
    if extends_match:
        analysis["superclass"] = extends_match.group(1).strip()
    
    # Extract fields with type information
    field_pattern = r'(public|protected|private|static|final)+\s+(?:static\s+)?(?:final\s+)?([\w.<>\[\]]+)\s+(\w+)\s*(?:=|;)'
    field_matches = re.finditer(field_pattern, source_code)
    
    for match in field_matches:
        modifiers = match.group(1).split()
        field_type = match.group(2)
        field_name = match.group(3)
        
        analysis["fields"].append({
            "name": field_name,
            "type": field_type,
            "modifiers": modifiers
        })
    
    return analysis


def process_class_with_enhanced_mcts(
    project_dir, prompt_dir, class_name, package_name, 
    max_iterations=5, target_coverage=100.0, 
    verify_bugs_mode="batch", prioritize_bugs=False):
    """
    Process single class test generation and optimization using a single Enhanced MCTS tree
    
    Parameters:
    project_dir (str): Project directory
    prompt_dir (str): Prompt directory
    class_name (str): Class name
    package_name (str): Package name
    max_iterations (int): Maximum iterations for the MCTS tree (higher for single tree)
    target_coverage (float): Target coverage percentage
    verify_bugs_mode (str): Bug verification strategy (immediate/batch/none)
    prioritize_bugs (bool): Whether to prioritize bug finding over coverage
    
    Returns:
    tuple: (success, coverage, has_errors, test_code)
    """
    logger.info(f"Starting to process class with Enhanced MCTS: {package_name}.{class_name}")
    
    # 1. Read test prompt file
    test_prompt_file = os.path.join(prompt_dir, f"{class_name}_test_prompt.txt")
    if not os.path.exists(test_prompt_file):
        test_prompt_file = os.path.join(prompt_dir, f"{class_name}.txt")
        if not os.path.exists(test_prompt_file):
            logger.error(f"Test prompt file not found: {class_name}_test_prompt.txt or {class_name}.txt")
            return False, 0.0, True, ""
    
    # 2. Find and read source code
    source_file = find_source_code(project_dir, class_name, package_name)
    if not source_file:
        logger.error(f"Source code file not found: {class_name}.java")
        return False, 0.0, True, ""
    
    source_code = read_source_code(source_file)
    if not source_code:
        logger.error(f"Failed to read source code")
        return False, 0.0, True, ""
    
    # 3. Generate initial tests
    logger.info("Generating initial test code with template-based approach")
    initial_test = generate_initial_test(test_prompt_file, source_code)
    
    if not initial_test:
        logger.error("Initial test generation failed")
        return False, 0.0, True, ""
    
    # 4. Use a single Enhanced MCTS tree with more iterations to improve test coverage
    logger.info("Starting Enhanced MCTS-guided test optimization with single tree")
    
    improved_test, best_coverage, has_errors = improve_test_coverage_with_enhanced_mcts(
        project_dir, prompt_dir, test_prompt_file, class_name, package_name, 
        initial_test, source_code, max_iterations, target_coverage,
        verify_bugs_mode, prioritize_bugs
    )
    
    # 5. Generate comprehensive summary 
    try:
        # Determine status
        status = "Success" if not has_errors and best_coverage >= target_coverage else "Partial Success"
        generate_test_summary(project_dir, class_name, package_name, best_coverage, has_errors, 1, status)
    except Exception as e:
        logger.error(f"Failed to generate test summary: {str(e)}")
    
    # 6. Output result summary
    logger.info(f"Class {package_name}.{class_name} processing completed with single MCTS tree")
    logger.info(f"Best coverage: {best_coverage:.2f}%")
    logger.info(f"Has errors: {has_errors}")
    logger.info(f"Final status: {status}")
    
    return True, best_coverage, has_errors, improved_test

def batch_process_classes_with_enhanced_mcts(
    project_dir, prompt_dir, output_file=None, 
    max_iterations=5, target_coverage=100.0,
    verify_bugs_mode="batch", prioritize_bugs=False,
    max_threads=1):
    """
    Batch process all classes in directory using a single Enhanced MCTS tree per class
    
    Parameters:
    project_dir (str): Project directory
    prompt_dir (str): Prompt directory
    output_file (str): Output result file
    max_iterations (int): Maximum iterations per MCTS tree (higher for single tree)
    target_coverage (float): Target coverage percentage
    verify_bugs_mode (str): Bug verification strategy (immediate/batch/none)
    prioritize_bugs (bool): Whether to prioritize bug finding over coverage
    max_threads (int): Maximum number of parallel threads (not implemented yet)
    
    Returns:
    list: Processing result list
    """
    import glob
    import re
    
    # Find all test prompt files
    prompt_files = glob.glob(os.path.join(prompt_dir, "*_test_prompt.txt"))
    prompt_files.extend(glob.glob(os.path.join(prompt_dir, "*.txt")))
    
    # Filter valid prompt files
    valid_files = [f for f in prompt_files if not any(x in f for x in 
                  ["_improved", "_history", "_summary", "_best", "_mcts", "_bug", "_critical", "_analysis"])]
    
    if not valid_files:
        logger.error(f"No test prompt files found in {prompt_dir}")
        return []
    
    logger.info(f"Found {len(valid_files)} test prompt files, starting batch processing with single MCTS tree per class")
    
    results = []
    success_count = 0
    
    for file_path in valid_files:
        # Extract class and package name
        class_name = os.path.basename(file_path).replace("_test_prompt.txt", "").replace(".txt", "")
        
        # Extract package from file content
        with open(file_path, 'r', encoding='utf-8') as file:
            content = file.read()
            package_match = re.search(r'Package:\s*([\w.]+)', content)
            package_name = package_match.group(1) if package_match else None
        
        if not package_name:
            logger.warning(f"Could not extract package name from {file_path}, skipping")
            continue
        
        logger.info(f"Starting processing with Enhanced MCTS: {package_name}.{class_name}")
        
        try:
            # Process with a single MCTS tree (more iterations)
            success, coverage, has_errors, test_code = process_class_with_enhanced_mcts(
                project_dir, prompt_dir, class_name, package_name, 
                max_iterations, target_coverage, 
                verify_bugs_mode, prioritize_bugs)
            
            if success and not has_errors and coverage >= target_coverage:
                success_count += 1
            
            # Record result
            result = {
                "class_name": class_name,
                "package_name": package_name,
                "coverage": coverage,
                "has_errors": has_errors,
                "success": success and not has_errors and coverage >= target_coverage,
                "method": "Enhanced MCTS Single Tree",
                "verify_bugs_mode": verify_bugs_mode,
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
            }
            results.append(result)
            
            # Save intermediate results
            if output_file:
                try:
                    with open(output_file, 'w', encoding='utf-8') as f:
                        json.dump(results, f, indent=2)
                except Exception as e:
                    logger.error(f"Failed to save intermediate results: {str(e)}")
            
        except Exception as e:
            logger.error(f"Error occurred while processing {class_name}: {str(e)}")
            logger.error(traceback.format_exc())
            
            results.append({
                "class_name": class_name,
                "package_name": package_name,
                "coverage": 0.0,
                "has_errors": True,
                "success": False,
                "method": "Enhanced MCTS Single Tree",
                "error": str(e),
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
            })
    
    # Create consolidated report
    create_consolidated_report(project_dir, results)
    
    # Output summary
    logger.info("Batch processing with Enhanced MCTS completed")
    logger.info(f"Total: {len(results)} classes")
    logger.info(f"Success: {success_count} classes")
    logger.info(f"Failed: {len(results) - success_count} classes")
    
    # Save final results
    if output_file:
        try:
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(results, f, indent=2)
            logger.info(f"Results saved to: {output_file}")
        except Exception as e:
            logger.error(f"Failed to save results: {str(e)}")
    
    return results


def compare_results(enhanced_results, standard_results, output_dir):
    """
    Generate detailed comparison report between Enhanced MCTS and standard approach
    
    Parameters:
    enhanced_results (list): Results from Enhanced MCTS
    standard_results (list): Results from standard approach
    output_dir (str): Output directory for report
    """
    # Create summary
    comparison = {
        "summary": {
            "total_classes": len(enhanced_results),
            "enhanced_successful": sum(1 for r in enhanced_results if r.get("success", False)),
            "standard_successful": sum(1 for r in standard_results if r.get("success", False)),
            "enhanced_avg_coverage": sum(r.get("coverage", 0.0) for r in enhanced_results) / max(len(enhanced_results), 1),
            "standard_avg_coverage": sum(r.get("coverage", 0.0) for r in standard_results) / max(len(standard_results), 1),
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
        },
        "class_comparisons": []
    }
    
    # Match classes between result sets
    enhanced_by_class = {(r.get("package_name", ""), r.get("class_name", "")): r for r in enhanced_results}
    standard_by_class = {(r.get("package_name", ""), r.get("class_name", "")): r for r in standard_results}
    
    all_classes = set(enhanced_by_class.keys()) | set(standard_by_class.keys())
    
    # Compare each class
    for package_name, class_name in sorted(all_classes):
        enhanced_result = enhanced_by_class.get((package_name, class_name), {})
        std_result = standard_by_class.get((package_name, class_name), {})
        
        enhanced_coverage = enhanced_result.get("coverage", 0.0)
        std_coverage = std_result.get("coverage", 0.0)
        
        comparison["class_comparisons"].append({
            "package_name": package_name,
            "class_name": class_name,
            "enhanced_coverage": enhanced_coverage,
            "standard_coverage": std_coverage,
            "difference": enhanced_coverage - std_coverage,
            "enhanced_has_errors": enhanced_result.get("has_errors", True),
            "standard_has_errors": std_result.get("has_errors", True),
            "enhanced_success": enhanced_result.get("success", False),
            "standard_success": std_result.get("success", False)
        })
    
    # Calculate improvements
    comparison["summary"]["coverage_improvement"] = comparison["summary"]["enhanced_avg_coverage"] - comparison["summary"]["standard_avg_coverage"]
    comparison["summary"]["success_improvement"] = comparison["summary"]["enhanced_successful"] - comparison["summary"]["standard_successful"]
    
    # Calculate success rate
    comparison["summary"]["enhanced_success_rate"] = (comparison["summary"]["enhanced_successful"] / comparison["summary"]["total_classes"]) * 100
    comparison["summary"]["standard_success_rate"] = (comparison["summary"]["standard_successful"] / comparison["summary"]["total_classes"]) * 100
    
    # Find notable improvements and regressions
    significant_improvements = []
    regressions = []
    
    for class_comp in comparison["class_comparisons"]:
        difference = class_comp["difference"]
        
        if difference >= 10.0:  # Significant improvement
            significant_improvements.append({
                "class": f"{class_comp['package_name']}.{class_comp['class_name']}",
                "improvement": difference,
                "enhanced_coverage": class_comp["enhanced_coverage"],
                "standard_coverage": class_comp["standard_coverage"]
            })
        elif difference <= -5.0:  # Regression
            regressions.append({
                "class": f"{class_comp['package_name']}.{class_comp['class_name']}",
                "regression": difference,
                "enhanced_coverage": class_comp["enhanced_coverage"],
                "standard_coverage": class_comp["standard_coverage"]
            })
    
    comparison["notable_improvements"] = significant_improvements
    comparison["regressions"] = regressions
    
    # Save comparison report
    report_file = os.path.join(output_dir, "enhanced_mcts_comparison_report.json")
    with open(report_file, 'w', encoding='utf-8') as f:
        json.dump(comparison, f, indent=2)
    
    # Create readable summary report
    summary_text = [
        "# Enhanced MCTS vs Standard Approach Comparison Report",
        f"Generated: {comparison['summary']['timestamp']}",
        "",
        "## Summary",
        f"Total classes processed: {comparison['summary']['total_classes']}",
        f"Enhanced MCTS successful classes: {comparison['summary']['enhanced_successful']} ({comparison['summary']['enhanced_success_rate']:.1f}%)",
        f"Standard approach successful classes: {comparison['summary']['standard_successful']} ({comparison['summary']['standard_success_rate']:.1f}%)",
        f"Enhanced MCTS average coverage: {comparison['summary']['enhanced_avg_coverage']:.2f}%",
        f"Standard approach average coverage: {comparison['summary']['standard_avg_coverage']:.2f}%",
        f"Overall coverage improvement: {comparison['summary']['coverage_improvement']:.2f}%",
        "",
        "## Notable Improvements",
    ]
    
    for imp in significant_improvements:
        summary_text.append(f"- {imp['class']}: +{imp['improvement']:.2f}% ({imp['standard_coverage']:.2f}% → {imp['enhanced_coverage']:.2f}%)")
    
    if not significant_improvements:
        summary_text.append("- None")
    
    summary_text.extend([
        "",
        "## Regressions",
    ])
    
    for reg in regressions:
        summary_text.append(f"- {reg['class']}: {reg['regression']:.2f}% ({reg['standard_coverage']:.2f}% → {reg['enhanced_coverage']:.2f}%)")
    
    if not regressions:
        summary_text.append("- None")
    
    # Save readable summary
    summary_file = os.path.join(output_dir, "comparison_summary.md")
    with open(summary_file, 'w', encoding='utf-8') as f:
        f.write("\n".join(summary_text))
    
    # Log summary information
    logger.info("Comparison Summary:")
    logger.info(f"Total classes: {comparison['summary']['total_classes']}")
    logger.info(f"Enhanced MCTS successful: {comparison['summary']['enhanced_successful']} ({comparison['summary']['enhanced_success_rate']:.1f}%)")
    logger.info(f"Standard successful: {comparison['summary']['standard_successful']} ({comparison['summary']['standard_success_rate']:.1f}%)")
    logger.info(f"Enhanced MCTS avg coverage: {comparison['summary']['enhanced_avg_coverage']:.2f}%")
    logger.info(f"Standard avg coverage: {comparison['summary']['standard_avg_coverage']:.2f}%")
    logger.info(f"Coverage improvement: {comparison['summary']['coverage_improvement']:.2f}%")
    logger.info(f"Notable improvements: {len(significant_improvements)}")
    logger.info(f"Regressions: {len(regressions)}")
    logger.info(f"Detailed reports saved to: {output_dir}")


def main():
    """Main function for Enhanced MCTS-integrated testing with single tree approach"""
    parser = argparse.ArgumentParser(description='Enhanced MCTS-guided LLM unit test generation with single tree')
    parser.add_argument('--project', required=True, help='Java project root directory')
    parser.add_argument('--prompt', required=True, help='Directory containing test prompts')
    parser.add_argument('--class', dest='class_name', help='Class name to test')
    parser.add_argument('--package', help='Package name of the class')
    parser.add_argument('--output', help='Output result file path')
    parser.add_argument('--batch', action='store_true', help='Batch process all classes')
    parser.add_argument('--max-iterations', type=int, default=30, 
                        help='Maximum iterations for the MCTS tree (higher for single tree approach)')
    parser.add_argument('--target-coverage', type=float, default=101.0, help='Target coverage percentage')
    parser.add_argument('--check-jacoco', action='store_true', help='Check and add Jacoco configuration')
    parser.add_argument('--compare', action='store_true', help='Compare against standard feedback approach')
    parser.add_argument('--prioritize-bugs', action='store_true', 
                        help='Prioritize finding bugs over coverage (default: False)')
    parser.add_argument('--verify-mode', choices=['immediate', 'batch', 'none'], default='batch',
                        help='When to verify potential bugs: during MCTS (immediate), after MCTS (batch), or not at all (none)')
    parser.add_argument('--max-threads', type=int, default=1, help='Maximum number of parallel threads for batch processing')
    parser.add_argument('--api-key', help='API key for LLM services')
    parser.add_argument('--verbose', action='store_true', help='Enable verbose logging')
    
    args = parser.parse_args()
    
    # Adjust logging level if verbose is specified
    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
        logging.info("Verbose logging enabled")
    
    # Set API key if provided
    if args.api_key:
        os.environ["ANTHROPIC_API_KEY"] = args.api_key
        os.environ["OPENAI_API_KEY"] = args.api_key
    
    # Check if project directory exists
    if not os.path.exists(args.project):
        parser.error(f"Project directory does not exist: {args.project}")
    
    # Check if prompt directory exists
    if not os.path.exists(args.prompt):
        parser.error(f"Prompt directory does not exist: {args.prompt}")
    
    # Reset LLM metrics before starting
    reset_llm_metrics()
    logger.info("Starting metrics tracking for LLM requests")
    
    # Check and add Jacoco configuration
    if args.check_jacoco:
        if not check_pom_for_jacoco(args.project):
            logger.info("Trying to add Jacoco plugin to pom.xml...")
            add_jacoco_to_pom(args.project)
    
    # Create results directory
    
    
    # Set verify mode
    verify_bugs_mode = args.verify_mode
    
    try:
        if args.batch:
            results_dir = os.path.join(args.project, "enhanced_mcts_results")
            os.makedirs(results_dir, exist_ok=True)
            # Batch process all classes with a single MCTS tree per class
            results = batch_process_classes_with_enhanced_mcts(
                args.project, 
                args.prompt, 
                os.path.join(results_dir, "batch_results.json") if not args.output else args.output,
                args.max_iterations, 
                args.target_coverage,
                verify_bugs_mode,
                args.prioritize_bugs,
                args.max_threads
            )
            
            if args.compare:
                # Import the standard approach function
                from feedback import batch_process_classes
                
                # Run comparison with standard approach
                logger.info("Running comparison with standard feedback approach...")
                standard_results = batch_process_classes(
                    args.project, 
                    args.prompt, 
                    os.path.join(results_dir, "standard_batch_results.json"),
                    args.max_iterations // 3,  # Use fewer iterations for standard approach
                    args.target_coverage
                )
                
                # Compare the results
                compare_results(results, standard_results, results_dir)
            
        elif args.class_name and args.package:
            # Process a single class with a single MCTS tree
            success, coverage, has_errors, test_code = process_class_with_enhanced_mcts(
                args.project, 
                args.prompt, 
                args.class_name, 
                args.package, 
                args.max_iterations, 
                args.target_coverage,
                verify_bugs_mode,
                args.prioritize_bugs
            )
            
            if success:
                status = "Success" if not has_errors and coverage >= args.target_coverage else "Partial Success"
                logger.info(f"Class {args.package}.{args.class_name} processed with status: {status}")
                logger.info(f"Coverage: {coverage:.2f}%")
                logger.info(f"Has errors: {has_errors}")
                
                if args.compare:
                    # Import the standard approach function
                    from feedback import process_class
                    
                    # Run comparison with standard approach
                    logger.info("Running comparison with standard feedback approach...")
                    std_success, std_coverage, std_has_errors, std_test_code = process_class(
                        args.project, 
                        args.prompt, 
                        args.class_name, 
                        args.package, 
                        args.max_iterations // 3,  # Use fewer iterations for standard approach 
                        args.target_coverage
                    )
                    
                    logger.info(f"Standard approach results - Coverage: {std_coverage:.2f}%, Errors: {std_has_errors}")
                    logger.info(f"Improvement with Enhanced MCTS: {coverage - std_coverage:.2f}%")
                    
                    # Save comparison results
                    comparison = {
                        "class_name": args.class_name,
                        "package_name": args.package,
                        "enhanced_mcts": {
                            "coverage": coverage,
                            "has_errors": has_errors,
                            "success": success and not has_errors and coverage >= args.target_coverage,
                            "verify_bugs_mode": verify_bugs_mode
                        },
                        "standard": {
                            "coverage": std_coverage,
                            "has_errors": std_has_errors,
                            "success": std_success and not std_has_errors and std_coverage >= args.target_coverage
                        },
                        "improvement": {
                            "coverage": coverage - std_coverage,
                            "success": (success and not has_errors) - (std_success and not std_has_errors)
                        }
                    }
                    
                    comparison_file = os.path.join(results_dir, f"{args.class_name}_comparison.json")
                    with open(comparison_file, 'w', encoding='utf-8') as f:
                        json.dump(comparison, f, indent=2)
                    logger.info(f"Comparison results saved to: {comparison_file}")
            else:
                logger.error(f"Failed to process class {args.package}.{args.class_name}")
        else:
            parser.error("Must specify --batch or both --class and --package")
            
        # Get and output LLM usage metrics
        llm_metrics = get_llm_metrics_summary()
        
        # Print metrics in a formatted way
        print("\n" + "=" * 80)
        print("LLM USAGE METRICS SUMMARY")
        print("=" * 80)
        print(f"Total LLM requests:         {llm_metrics['total_requests']}")
        print(f"Maximum prompt tokens:      {llm_metrics['max_token_size']}")
        print(f"Minimum prompt tokens:      {llm_metrics['min_token_size']}")
        print(f"Average prompt tokens:      {llm_metrics['avg_token_size']:.2f}")
        print(f"Total processing time:      {llm_metrics['total_time_minutes']:.2f} minutes ({llm_metrics['total_time_seconds']:.2f} seconds)")
        print(f"Average request time:       {llm_metrics['avg_request_time']:.2f} seconds")
        print("=" * 80)
        
        # Save metrics to a file
        if args.batch:
            metrics_file = os.path.join(results_dir, "llm_metrics.json")
            detailed_metrics_file = os.path.join(results_dir, "llm_detailed_metrics.json")
        else:
            results_dir = os.path.join(args.project, "enhanced_mcts_results")
            os.makedirs(results_dir, exist_ok=True)
            metrics_file = os.path.join(results_dir, f"{args.class_name}_llm_metrics.json")
            detailed_metrics_file = os.path.join(results_dir, f"{args.class_name}_llm_detailed_metrics.json")
            
        with open(metrics_file, 'w', encoding='utf-8') as f:
            json.dump(llm_metrics, f, indent=2)
        
        # Save detailed metrics
        detailed_metrics = log_detailed_metrics(detailed_metrics_file)
        
        print(f"Metrics saved to: {metrics_file}")
        print(f"Detailed metrics saved to: {detailed_metrics_file}")
            
    except KeyboardInterrupt:
        logger.info("Process interrupted by user")
        
        # Still output metrics even if interrupted
        try:
            llm_metrics = get_llm_metrics_summary()
            print("\n" + "=" * 80)
            print("LLM USAGE METRICS SUMMARY (INTERRUPTED)")
            print("=" * 80)
            print(f"Total LLM requests:         {llm_metrics['total_requests']}")
            print(f"Maximum prompt tokens:      {llm_metrics['max_token_size']}")
            print(f"Minimum prompt tokens:      {llm_metrics['min_token_size']}")
            print(f"Average prompt tokens:      {llm_metrics['avg_token_size']:.2f}")
            print(f"Total processing time:      {llm_metrics['total_time_minutes']:.2f} minutes ({llm_metrics['total_time_seconds']:.2f} seconds)")
            print(f"Average request time:       {llm_metrics['avg_request_time']:.2f} seconds")
            print("=" * 80)
        except:
            logger.error("Could not output metrics after interruption")
            
        sys.exit(1)
    except Exception as e:
        logger.error(f"An error occurred: {str(e)}")
        logger.error(traceback.format_exc())
        
        # Still try to output metrics even if there was an error
        try:
            llm_metrics = get_llm_metrics_summary()
            print("\n" + "=" * 80)
            print("LLM USAGE METRICS SUMMARY (ERROR OCCURRED)")
            print("=" * 80)
            print(f"Total LLM requests:         {llm_metrics['total_requests']}")
            print(f"Maximum prompt tokens:      {llm_metrics['max_token_size']}")
            print(f"Minimum prompt tokens:      {llm_metrics['min_token_size']}")
            print(f"Average prompt tokens:      {llm_metrics['avg_token_size']:.2f}")
            print(f"Total processing time:      {llm_metrics['total_time_minutes']:.2f} minutes ({llm_metrics['total_time_seconds']:.2f} seconds)")
            print(f"Average request time:       {llm_metrics['avg_request_time']:.2f} seconds")
            print("=" * 80)
        except:
            logger.error("Could not output metrics after error")
            
        sys.exit(1)


if __name__ == "__main__":
    main()