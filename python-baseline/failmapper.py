#!/usr/bin/env python3
"""
FailMapper: Failure-Aware Monte carlo Bug Detection Architecture

This module serves as the main entry point for the FailMapper framework, which enhances
MCTS-based test generation with failure-aware capabilities to improve detection of
bugs in Java code.

The framework integrates static analysis, failure model extraction, and enhanced MCTS
to generate tests that specifically target bugs.
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
from extractor import Extractor
from failure_scenarios import FS_Detector
from fa_mcts import FA_MCTS
from test_generation_strategies import TestStrategySelector
from feedback import (
    generate_initial_test,read_source_code, find_source_code, detect_project_type,
    check_build_for_jacoco, add_jacoco_to_build,
    read_test_prompt_file, reset_llm_metrics, get_llm_metrics_summary,
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("failmapper")

class FailMapper:
    """
    Main FailMapper framework class that orchestrates the components for
    failure-aware test generation.
    """
    
    def __init__(self, project_dir, prompt_dir, analysis_dir=None, 
                 max_iterations=20, target_coverage=100.0,
                 verify_mode="batch", prioritize_bugs=True,
                 f_weight=2.0, bugs_threshold=15, verbose=False,
                 project_type='maven'):
        """
        Initialize the FailMapper framework
        
        Parameters:
        project_dir (str): Java project directory
        prompt_dir (str): Directory with test prompts
        analysis_dir (str): Directory for static analysis results (optional)
        max_iterations (int): Maximum MCTS iterations
        target_coverage (float): Target coverage percentage
        verify_mode (str): Bug verification mode (immediate/batch/none)
        prioritize_bugs (bool): Whether to prioritize bug finding over coverage
        f_weight (float): Weight for failure-related rewards (higher = more focus on failure)
        failure_bugs_threshold (int): Number of failure bugs to find before terminating search
        verbose (bool): Enable verbose logging
        """
        self.project_dir = project_dir
        self.prompt_dir = prompt_dir
        self.analysis_dir = analysis_dir or os.path.join(project_dir, "lambda_analysis")
        self.max_iterations = max_iterations
        self.target_coverage = target_coverage
        self.verify_mode = verify_mode
        self.prioritize_bugs = prioritize_bugs
        self.f_weight = f_weight
        self.bugs_threshold = bugs_threshold
        self.verbose = verbose
        self.project_type = project_type
        
        # Create analysis directory if it doesn't exist
        os.makedirs(self.analysis_dir, exist_ok=True)
        
        # Initialize metrics
        self.metrics = {
            "classes_processed": 0,
            "bugs_found": 0,
            "real_bugs_found": 0,
            "avg_coverage": 0.0,
            "execution_time": 0,
            "bug_patterns": defaultdict(int)
        }
        
        # Set logging level
        if verbose:
            logging.getLogger().setLevel(logging.DEBUG)
        
        logger.info(f"Initialized FailMapper framework with f_weight={f_weight}, bugs_threshold={bugs_threshold}")
    
    def process_class(self, class_name, package_name):
        """
        use failure-aware test generation to process a single class
        
        Parameters:
        class_name (str): the name of the class to process
        package_name (str): the package name
        
        Returns:
        tuple: (success, coverage, bug_count, logical_bug_count, test_code)
        """
        logger.info(f"processing class: {package_name}.{class_name}")
        start_time = time.time()
        
        # 1. 查找源代码
        source_file = find_source_code(self.project_dir, class_name, package_name)
        if not source_file:
            logger.error(f"source code file not found: {package_name}.{class_name}")
            # create an empty failure model, so that the test generation can continue
            logger.warning("since the source file is missing, create an empty failure model")
            f_model = Extractor(
                source_code="", 
                class_name=class_name, 
                package_name=package_name
            )
            return False, 0.0, 0, 0, ""
        
        # 2. read the source code
        source_code = read_source_code(source_file)
        if not source_code or not source_code.strip():
            logger.error(f"cannot read source code or file is empty: {package_name}.{class_name}")
            # create an empty failure model
            logger.warning("since the source code is empty, create an empty failure model")
            f_model = Extractor(
                source_code="", 
                class_name=class_name, 
                package_name=package_name
            )
            return False, 0.0, 0, 0, ""
        
        # 3. extract the failure model
        logger.info("extracting failure model from the source code")
        try:
            if source_code is None or not source_code.strip():
                logger.warning("source code is empty or None, create an empty failure model")
                f_model = Extractor(
                    source_code="", 
                    class_name=class_name, 
                    package_name=package_name
                )
            else:
                f_model = Extractor(
                    source_code=source_code, 
                    class_name=class_name, 
                    package_name=package_name
                )
                
            if not f_model.boundary_conditions and not f_model.operations:
                logger.warning("the failure model is empty. this may affect the analysis quality.")
        except Exception as e:
            logger.error(f"error creating failure model: {str(e)}")
            # create an empty failure model to avoid None reference
            f_model = Extractor(
                source_code="", 
                class_name=class_name, 
                package_name=package_name
            )
        
        # 4. detect failure bug patterns
        logger.info("detecting failure bug patterns")
        try:
            pattern_detector = FS_Detector(
                source_code=source_code, 
                class_name=class_name, 
                package_name=package_name, 
                f_model=f_model
            )
            failures = pattern_detector.detect_patterns()
            
            # 记录检测到的模式
            if failures:
                logger.info(f"detected {len(failures)} potential failure scenarios:")
                for pattern in failures:
                    logger.info(f"  - {pattern['type']} (risk: {pattern['risk_level']}) in line {pattern['location']}")
                    self.metrics["bug_patterns"][pattern['type']] += 1
            
            if not failures:
                logger.warning("no failure scenarios detected. this may indicate simple code or limited detection capability.")
                failures = []  # ensure it is an empty list rather than None
                
        except Exception as e:
            logger.error(f"error detecting failure scenarios: {str(e)}")
            logger.error(traceback.format_exc())
            # create an empty list of failure scenarios to continue execution
            failures = []
        
        # 5. read the test prompt
        logger.info("reading test prompt")
        test_prompt_file = os.path.join(self.prompt_dir, f"{class_name}_test_prompt.txt")
        if not os.path.exists(test_prompt_file):
            test_prompt_file = os.path.join(self.prompt_dir, f"{class_name}.txt")
            if not os.path.exists(test_prompt_file):
                logger.error(f"test prompt file not found: {class_name}_test_prompt.txt or {class_name}.txt")
                return False, 0.0, 0, 0, ""
        
        # read the test prompt content
        test_prompt_content = read_test_prompt_file(self.prompt_dir, class_name)
        if not test_prompt_content:
            logger.error(f"cannot read test prompt from {test_prompt_file}")
            return False, 0.0, 0, 0, ""
        
        # 5. generate the initial test
        logger.info("generating initial test")
        initial_test = generate_initial_test(test_prompt_file, source_code)
        
        if not initial_test:
            logger.error("initial test generation failed")
            return False, 0.0, 0, 0, ""
        
        # 6. create the strategy selector based on the detected failure scenarios
        strategy_selector = TestStrategySelector(failures, f_model)
        
        # 7. run the failure-aware MCTS
        logger.info("running failure-aware MCTS for test generation")
        mcts = FA_MCTS(
            project_dir=self.project_dir,
            prompt_dir=self.prompt_dir,
            class_name=class_name,
            package_name=package_name,
            initial_test_code=initial_test,
            source_code=source_code,
            test_prompt=test_prompt_content,
            f_model=f_model,
            failures=failures,
            strategy_selector=strategy_selector,
            max_iterations=self.max_iterations,
            exploration_weight=1.0,
            verify_bugs_mode=self.verify_mode,
            focus_on_bugs=self.prioritize_bugs,
            f_weight=self.f_weight,
            bugs_threshold=self.bugs_threshold,
            project_type=self.project_type
        )
        
        # Set MCTS instance reference in strategy selector for global state tracking
        strategy_selector._mcts_instance = mcts
        
        # run the MCTS search
        final_test, coverage = mcts.run_search()
        
        # get the verified bug list
        verified_bugs = mcts.verified_bug_methods
        
        # calculate the bugs
        real_bugs = [bug for bug in verified_bugs if 
                            bug.get("is_real_bug", False)]
        
        # update the metrics
        self.metrics["classes_processed"] += 1
        self.metrics["bugs_found"] += len(verified_bugs)
        self.metrics["real_bugs_found"] += len(real_bugs)
        self.metrics["avg_coverage"] = ((self.metrics["avg_coverage"] * (self.metrics["classes_processed"] - 1)) + coverage) / self.metrics["classes_processed"]
        
        # generate and save the summary
        execution_time = time.time() - start_time
        self.metrics["execution_time"] += execution_time
        
        # create the result summary for this class
        result_summary = {
            "class_name": class_name,
            "package_name": package_name,
            "coverage": coverage,
            "total_bugs": len(verified_bugs),
            "bugs": len(real_bugs),
            "scenarios_detected": len(failures),
            "execution_time": execution_time,
            "failures": [pattern['type'] for pattern in failures]
        }
        
        # save the result summary
        result_file = os.path.join(self.analysis_dir, f"{class_name}_lambda_result.json")
        with open(result_file, 'w', encoding='utf-8') as f:
            json.dump(result_summary, f, indent=2)
        logger.info(f"result summary saved to: {result_file}")
        
        logger.info(f"{package_name}.{class_name} processing completed")
        
        return True, coverage, len(verified_bugs), len(real_bugs), final_test


    def batch_process(self, output_file=None):
        """
        Batch process all classes in the prompt directory
        
        Parameters:
        output_file (str): Path to save batch results
        
        Returns:
        list: Processing results
        """
        import glob
        import re
        
        # Find all test prompt files
        prompt_files = glob.glob(os.path.join(self.prompt_dir, "*_test_prompt.txt"))
        prompt_files.extend(glob.glob(os.path.join(self.prompt_dir, "*.txt")))
        
        # Filter valid prompt files
        # valid_files = [f for f in prompt_files if not any(x in f for x in 
        #               ["_improved", "_history", "_summary", "_best", "_mcts", 
        #                "_bug", "_critical", "_analysis", "_lambda"])]
        
        if not prompt_files:
            logger.error(f"No test prompt files found in {self.prompt_dir}")
            return []
        
        logger.info(f"Found {len(prompt_files)} test prompt files, starting batch processing")
        
        results = []
        logical_bug_count = 0
        total_bug_count = 0
        
        for file_path in prompt_files:
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
            
            try:
                # Process class
                success, coverage, bug_count, logical_count, test_code = self.process_class(
                    class_name, package_name)
                
                # Update counts
                total_bug_count += bug_count
                logical_bug_count += logical_count
                
                # Record result
                result = {
                    "class_name": class_name,
                    "package_name": package_name,
                    "coverage": coverage,
                    "bug_count": bug_count,
                    "logical_bug_count": logical_count,
                    "success": success,
                    "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
                }
                results.append(result)
                
                # Save intermediate results
                if output_file:
                    try:
                        # Check if output_file is a directory
                        intermediate_output = output_file
                        if os.path.isdir(output_file):
                            intermediate_output = os.path.join(output_file, "lambda_batch_results.json")
                        
                        with open(intermediate_output, 'w', encoding='utf-8') as f:
                            json.dump(results, f, indent=2)
                    except Exception as e:
                        logger.error(f"Failed to save intermediate results: {str(e)}")
                
            except Exception as e:
                logger.error(f"Error processing {class_name}: {str(e)}")
                logger.error(traceback.format_exc())
                
                results.append({
                    "class_name": class_name,
                    "package_name": package_name,
                    "error": str(e),
                    "success": False,
                    "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
                })
        
        # Generate final summary
        logger.info("Batch processing completed:")
        logger.info(f"Total classes processed: {len(results)}")
        logger.info(f"Total bugs found: {total_bug_count}")
        
        # Save final metrics
        metrics_file = os.path.join(self.analysis_dir, "failmapper_metrics.json")
        with open(metrics_file, 'w', encoding='utf-8') as f:
            json.dump(self.metrics, f, indent=2)
        logger.info(f"Final metrics saved to: {metrics_file}")
        
        # Save final results
        if output_file:
            # Check if output_file is a directory
            if os.path.isdir(output_file):
                output_file = os.path.join(output_file, "failmapper_batch_results.json")
            
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(results, f, indent=2)
            logger.info(f"Final results saved to: {output_file}")
        
        return results

def main():
    """
    Command line entry point for the FailMapper framework
    """
    parser = argparse.ArgumentParser(description="FailMapper: Failure-Aware Monte carlo Bug Detection Architecture")
    parser.add_argument("--project", required=True, help="Java project root directory")
    parser.add_argument("--prompt", required=True, help="Directory containing test prompts")
    parser.add_argument("--analysis", help="Directory for static analysis results")
    parser.add_argument("--class", dest="class_name", help="Specific class name to test")
    parser.add_argument("--package", help="Package name of the class")
    parser.add_argument("--output", help="Output result file path")
    parser.add_argument("--batch", action="store_true", help="Batch process all classes")
    parser.add_argument("--max-iterations", type=int, default=27, help="Maximum MCTS iterations")
    parser.add_argument("--target-coverage", type=float, default=100.0, help="Target coverage percentage")
    parser.add_argument("--verify-mode", choices=["immediate", "batch", "none"], default="batch",
                        help="When to verify bugs: during MCTS (immediate), after (batch), or not at all (none)")
    parser.add_argument("--f-weight", type=float, default=2.0, 
                        help="Weight for failure-related rewards (higher = more focus on failure)")
    parser.add_argument("--bugs-threshold", type=int, default=1000, 
                        help="Number of bugs to find before terminating search")
    parser.add_argument("--project-type", choices=['maven', 'gradle'], 
                        help='Project type (maven or gradle). If not specified, will auto-detect.')
    parser.add_argument("--verbose", action="store_true", help="Enable verbose logging")
    
    args = parser.parse_args()
    
    # Detect or use specified project type
    if args.project_type:
        project_type = args.project_type
    else:
        project_type = detect_project_type(args.project)
        if project_type == 'unknown':
            logger.warning("Could not detect project type. Defaulting to Maven.")
            project_type = 'maven'
        else:
            logger.info(f"Auto-detected project type: {project_type}")
    
    # Initialize the framework
    framework = FailMapper(
        project_dir=args.project,
        prompt_dir=args.prompt,
        analysis_dir=args.analysis,
        max_iterations=args.max_iterations,
        target_coverage=args.target_coverage,
        verify_mode=args.verify_mode,
        f_weight=args.f_weight,
        bugs_threshold=args.bugs_threshold,
        verbose=args.verbose,
        project_type=project_type
    )
    
    # Check Jacoco configuration
    if check_build_for_jacoco(args.project, project_type):
        logger.info(f"JaCoCo plugin is configured in the {project_type} project")
    else:
        logger.warning(f"JaCoCo plugin not found in {project_type} project, attempting to add it")
        add_jacoco_to_build(args.project, project_type)
    
    # Reset LLM metrics
    reset_llm_metrics()
    
    # Process classes
    if args.batch:
        framework.batch_process(args.output)
    elif args.class_name and args.package:
        framework.process_class(args.class_name, args.package)
    else:
        parser.error("Must specify --batch or both --class and --package")
    
    # Output LLM usage metrics
    metrics = get_llm_metrics_summary()
    logger.info("LLM Usage Metrics:")
    logger.info(f"Total requests: {metrics['total_requests']}")
    logger.info(f"Avg. token size: {metrics['avg_token_size']:.1f}")
    logger.info(f"Total time: {metrics['total_time_minutes']:.2f} minutes")

if __name__ == "__main__":
    main()
