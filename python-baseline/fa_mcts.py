#!/usr/bin/env python3
"""
Failure-Aware Monte Carlo Tree Search

This module implements a Failure-Aware Monte Carlo Tree Search (MCTS) algorithm
for generating tests that specifically target failure vulnerabilities in Java code.
The algorithm enhances traditional MCTS with failure-aware components to improve
the detection of bugs.
"""

import os
import re
import time
import json
import logging
import random
import traceback
import numpy as np
from collections import defaultdict
from datetime import datetime
from enhanced_mcts_test_generator import EnhancedMCTSTestGenerator
from test_state import FATestState
from feedback import (
    call_anthropic_api, call_gpt_api, call_deepseek_api,
    run_tests_with_jacoco, save_test_code
)
from bug_verifier import BugVerifier

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("fa_mcts")

class FA_MCTSNode:
    """
    Node in the Failure-Aware MCTS tree
    
    Enhanced with failure-specific rewards and heuristics to improve
    the detection of failure-related vulnerabilities.
    """
    
    def __init__(self, state, parent=None, action=None):
        """
        Initialize node with state and parent
        
        Parameters:
        state (TestState): Current test state
        parent (FA_MCTSNode): Parent node (None for root)
        action (str): Action taken to reach this state
        """
        self.state = state
        self.parent = parent
        self.action = action
        self.children = []
        self.wins = 0.0
        self.visits = 0
        
        # Logic-specific rewards
        self.logic_bug_rewards = 0.0  # Additional rewards for finding logical bugs
        self.failure_coverage_rewards = 0.0  # Rewards for covering logical constructs
        self.high_risk_pattern_rewards = 0.0  # Rewards for covering high-risk patterns
        
        # Logic-specific metrics
        self.bugs_found = 0
        self.covered_patterns = set()
        self.covered_branch_conditions = set()
        
        # Track bug types found by this node and its children
        self.bug_types_found = set()
        
        # Track whether this node found a new test path or behavior
        self.is_novel = False
        self.expanded = False
        self.used_action = []
    
    def has_compilation_errors(self):
        """
        Check if the current state has compilation errors
        
        Returns:
        bool: True if there are compilation errors, False otherwise
        """
        has_errors = (self.state and 
                     hasattr(self.state, "compilation_errors") and 
                     self.state.compilation_errors)
        
        # Track compilation error fix attempts
        if not hasattr(self, 'compilation_fix_attempts'):
            self.compilation_fix_attempts = 0
        
        # Debug logging
        if self.state:
            if hasattr(self.state, "compilation_errors"):
                if self.state.compilation_errors:
                    logger.info(f"Node has compilation errors: {len(self.state.compilation_errors)} errors")
                    logger.debug(f"Compilation errors: {self.state.compilation_errors[:3]}")
                else:
                    logger.debug("Node has empty compilation_errors list")
            else:
                logger.debug("Node state does not have compilation_errors attribute")
        else:
            logger.debug("Node state is None")
            
        return has_errors
    
    def generate_possible_actions(self, test_prompt, source_code, uncovered_data=None, 
                               f_model=None, failures=None, strategy_selector=None):
        """
        Generate possible actions (test methods) from current state
        
        Parameters:
        test_prompt (str): Test generation prompt
        source_code (str): Source code being tested
        uncovered_data (dict): Information about uncovered code
        model (Extractor): Failure model
        patterns (list): Detected failure bug patterns
        strategy_selector (TestStrategySelector): Strategy selector
        
        Returns:
        list: List of possible actions
        """
        possible_actions = []
        business_logic_issues = []
        
        # Check for compilation errors and prioritize fixing them
        if self.has_compilation_errors():
            # Get reference to the MCTS instance for global tracking
            mcts_instance = getattr(strategy_selector, '_mcts_instance', None) if strategy_selector else None
            
            # Check global compilation fix attempts
            MAX_FIX_ATTEMPTS = 10
            global_attempts = 0
            
            if mcts_instance and hasattr(mcts_instance, 'global_compilation_fix_attempts'):
                global_attempts = mcts_instance.global_compilation_fix_attempts
                
                # Check if we've exceeded the global limit
                if global_attempts >= MAX_FIX_ATTEMPTS:
                    logger.warning(f"Reached maximum global compilation fix attempts ({MAX_FIX_ATTEMPTS}). Exploring alternative paths.")
                    # Don't return compilation fix action, continue with other actions
                else:
                    # Check if this specific path has failed before
                    path_signature = self._get_path_signature()
                    if mcts_instance and path_signature in mcts_instance.failed_fix_paths:
                        logger.warning(f"This path has already failed to fix compilation errors. Exploring alternatives.")
                        # Don't return compilation fix action, continue with other actions
                    else:
                        # Increment global counter
                        mcts_instance.global_compilation_fix_attempts += 1
                        
                        logger.info(f"Detected compilation errors, prioritizing compilation error fixing (global attempt {global_attempts + 1}/{MAX_FIX_ATTEMPTS})")
                        
                        action = {
                            "type": "fix_compilation_errors",
                            "description": "Fix compilation errors in test code",
                            "errors": self.state.compilation_errors,  # Include errors for context
                            "attempt": global_attempts + 1,
                            "path_signature": path_signature
                        }

                        possible_actions.append(action)
                        return possible_actions
            else:
                # Fallback if we can't access global state
                logger.warning("Cannot access global compilation fix tracking. Using local attempt.")
                action = {
                    "type": "fix_compilation_errors",
                    "description": "Fix compilation errors in test code",
                    "errors": self.state.compilation_errors,
                    "attempt": 1
                }
                possible_actions.append(action)
                return possible_actions

        if hasattr(self.state, 'business_logic_analysis') and self.state.business_logic_analysis:
            business_logic_issues = self.state.business_logic_analysis.get('potential_bugs', [])
            # print("--------------------------------")
            # print("business_logic_issues in generate_possible_actions:")
            # print(business_logic_issues)
            # print("--------------------------------")
            

        # Get strategies from selector based on current state
        if strategy_selector:
            strategies = strategy_selector.select_strategies(
                self.state, 
                self.covered_patterns, 
                self.covered_branch_conditions,
                business_logic_issues
            )
        else:
            # Default strategy if no selector provided
            strategies = [
                {"id": "boundary_testing", "name": "Boundary Value Testing", "weight": 1.0},
                {"id": "expression", "name": "Expression Testing", "weight": 1.0},
                {"id": "exception_handling", "name": "Exception Path Testing", "weight": 0.7}
            ]
        
        for issue in business_logic_issues:
            print("--------------------------------")
            print("issue in generate_possible_actions:")
            print(issue)
            print("--------------------------------")
            
            action = {
                "type": "business_logic_test",
                "issue_type": issue.get('type', 'unknown'),
                "method": issue.get('method', ''),
                "description": f"Test for potential business logic issue: {issue.get('description', '')}",
                "confidence": issue.get('confidence', 0),
                "business_logic": True  # Flag to identify these special actions
            }
            possible_actions.append(action)

        # Get currently uncovered code if available
        uncovered_lines = []
        if uncovered_data and "uncovered_lines" in uncovered_data:
            uncovered_lines = uncovered_data["uncovered_lines"]
        
        # Include state-specific actions targeting interesting lines
        if uncovered_lines:
            # Select some random uncovered lines to focus on
            selected_lines = random.sample(
                uncovered_lines, 
                min(5, len(uncovered_lines))
            )
            
            for line_info in selected_lines:
                line_num = line_info.get("line", 0)
                content = line_info.get("content", "").strip()
                
                # Skip empty or irrelevant lines
                if not content or content in ["}", "{", "//", "/*", "*/"]:
                    continue
                
                # Create targeted action for this line
                line_action = {
                    "type": "target_line",
                    "line": line_num,
                    "content": content,
                    "description": f"Target uncovered line {line_num}: {content[:40]}..."
                }
                possible_actions.append(line_action)
        
        # Add strategy-based actions
        for strategy in strategies:
            strategy_id = strategy.get("id", "unknown")
            strategy_weight = strategy.get("weight", 1.0)
            
            # Skip strategies with very low weight
            if strategy_weight < 0.1:
                continue
                
            # Add focused strategy actions
            if strategy_id == "boundary_testing" and f_model:
                # Add boundary condition testing actions
                boundary_conditions = f_model.boundary_conditions
                if boundary_conditions:
                    # Select a few boundary conditions to target
                    selected_conditions = random.sample(
                        boundary_conditions,
                        min(2, len(boundary_conditions))
                    )
                    
                    for condition in selected_conditions:
                        condition_str = condition.get("condition", "")
                        line_num = condition.get("line", 0)
                        
                        if not condition_str:
                            continue
                            
                        action = {
                            "type": "boundary_test",
                            "condition": condition_str,
                            "line": line_num,
                            "strategy": strategy_id,
                            "description": f"Test boundary condition at line {line_num}: {condition_str[:40]}..."
                        }
                        possible_actions.append(action)
            
            elif strategy_id == "expression" and f_model:
                # Add operation testing actions
                operations = f_model.operations
                if operations:
                    # Select a few operations to target
                    selected_operations = random.sample(
                        operations,
                        min(2, len(operations))
                    )
                    
                    for operation in selected_operations:
                        operation_str = operation.get("condition", "")
                        line_num = operation.get("line", 0)
                        
                        if not operation_str:
                            continue
                            
                        action = {
                            "type": "expression_test",
                            "operation": operation_str,
                            "line": line_num,
                            "strategy": strategy_id,
                            "description": f"Test operation at line {line_num}: {operation_str[:40]}..."
                        }
                        possible_actions.append(action)
            
            elif strategy_id == "exception_handling":
                # Add exception handling test actions
                action = {
                    "type": "exception_test",
                    "strategy": strategy_id,
                    "description": "Generate tests for exception paths"
                }
                possible_actions.append(action)
            
            elif strategy_id == "data_validation":
                # Add data validation test actions
                action = {
                    "type": "data_validation_test",
                    "strategy": strategy_id,
                    "description": "Generate tests for data validation edge cases"
                }
                possible_actions.append(action)
            
            elif strategy_id == "resource_management":
                # Add resource management test actions
                action = {
                    "type": "resource_management_test",
                    "strategy": strategy_id,
                    "description": "Generate tests for resource management issues"
                }
                possible_actions.append(action)
            
            elif strategy_id == "state_transition":
                # Add state transition test actions
                action = {
                    "type": "state_transition_test",
                    "strategy": strategy_id, 
                    "description": "Generate tests for state transitions"
                }
                possible_actions.append(action)
        
        # Add targeted actions for failure scenarios
        if failures:
            # Filter to high-risk patterns
            high_risk_patterns = [p for p in failures if p.get("risk_level") == "high"]
            if high_risk_patterns:
                # Select a few high-risk patterns to target
                selected_patterns = random.sample(
                    high_risk_patterns,
                    min(2, len(high_risk_patterns))
                )
                
                for pattern in selected_patterns:
                    pattern_type = pattern.get("type", "unknown")
                    line_num = pattern.get("location", 0)
                    description = pattern.get("description", "")
                    
                    action = {
                        "type": "bug_pattern_test",
                        "pattern_type": pattern_type,
                        "line": line_num,
                        "description": f"Test for {pattern_type} bug pattern at line {line_num}: {description[:40]}..."
                    }
                    possible_actions.append(action)
        
        # Add general exploration action
        if not possible_actions or random.random() < 0.2:  # 20% chance to add exploration
            action = {
                "type": "general_exploration",
                "description": "General test exploration"
            }
            possible_actions.append(action)
            
        # Avoid empty action list
        if not possible_actions:
            action = {
                "type": "fallback",
                "description": "Fallback test generation"
            }
            possible_actions.append(action)
        print("--------------------------------")
        print("used_action in generate_possible_actions:")
        print(self.used_action)
        print("--------------------------------")
        possible_actions = [x for x in possible_actions if x not in self.used_action]
        return possible_actions

    def is_fully_expanded(self):
        """Check if all possible child actions have been explored"""
        return self.expanded
    
    def best_child(self, exploration_weight=1.0, f_weight=1.0):
        """
        Select best child node using UCB1 formula with logic enhancements
        
        Parameters:
        exploration_weight (float): Weight for exploration term
        f_weight (float): Weight for logic-specific rewards
        
        Returns:
        FA_MCTSNode: Best child node
        """
        if not self.children:
            return None
            
        def ucb_score(child):
            # Base UCB1 score
            exploitation = child.wins / child.visits if child.visits > 0 else 0.0
            exploration = exploration_weight * (2 * (self.visits / child.visits) ** 0.5) if child.visits > 0 else float('inf')
            
            # Logic-specific rewards with decay factor
            logic_bonus = 0.0
            if child.visits > 0:
                # Add reward for logical bugs found
                logic_bug_term = child.logic_bug_rewards / child.visits
                
                # Add reward for logic coverage
                logic_coverage_term = child.failure_coverage_rewards / child.visits
                
                # Add reward for high-risk pattern coverage
                high_risk_term = child.high_risk_pattern_rewards / child.visits
                
                # Add novelty bonus for finding new paths
                novelty_bonus = 0.2 if child.is_novel else 0.0
                
                # Add: visits decay factor - reduce reward as visits increase
                visits_decay = 1.0 / (1.0 + 0.1 * child.visits)
                
                # Add: consecutive failure penalty
                failure_penalty = 1.0
                if hasattr(child, 'consecutive_failures') and child.consecutive_failures > 0:
                    failure_penalty = max(0.3, 1.0 - (0.2 * child.consecutive_failures))
                
                # Add: strategy diversity bonus
                diversity_bonus = 0.0
                if hasattr(child, 'action') and hasattr(self, 'last_action_type'):
                    if isinstance(child.action, dict) and 'type' in child.action:
                        if child.action['type'] != self.last_action_type:
                            diversity_bonus = 0.15  # reward for selecting different strategy types
                
                # Combined logic bonus with decay and penalties
                logic_bonus = f_weight * (
                    (logic_bug_term + logic_coverage_term + high_risk_term + novelty_bonus) * 
                    visits_decay * failure_penalty
                ) + diversity_bonus
            
            # Return combined score
            return exploitation + exploration + logic_bonus
            
        # Return child with highest UCB score
        return max(self.children, key=ucb_score)

    def add_child(self, state, action):
        """
        Add a new child node
        
        Parameters:
        state (FATestState): New state
        action (dict): Action taken to reach the state
        
        Returns:
        FA_MCTSNode: New child node
        """
        # Create new child node
        child = FA_MCTSNode(state, self, action)
        
        # Add to children list
        self.children.append(child)
        
        # Check if the action leads to novel state
        if state:
            # Check if this action found new bugs
            if state.has_bugs:
                child.is_novel = True
                
                # Update found bug types
                for bug in state.logical_bugs:
                    bug_type = bug.get("bug_type", "unknown")
                    self.bug_types_found.add(bug_type)
                    
            # Check if this action covered new patterns
            if hasattr(state, "covered_failures") and state.covered_failures:
                new_patterns = state.covered_failures - self.covered_patterns
                if new_patterns:
                    child.is_novel = True
                    
            # Check if this action covered new branch conditions
            if hasattr(state, "covered_branch_conditions") and state.covered_branch_conditions:
                new_conditions = state.covered_branch_conditions - self.covered_branch_conditions
                if new_conditions:
                    child.is_novel = True
        
        # Return the new child node
        return child
    
    def _get_path_signature(self):
        """
        Get a unique signature for this node's path from root
        
        Returns:
        str: Path signature
        """
        path = []
        current = self
        while current.parent:
            if current.action and isinstance(current.action, dict):
                action_type = current.action.get('type', 'unknown')
                path.append(action_type)
            current = current.parent
        path.reverse()
        return "->".join(path)
    
    def update(self, reward, bug_type=None, pattern_coverage=None, branch_coverage=None, has_error=False):
        """
        Update node statistics after simulation
        
        Parameters:
        reward (float): Reward value
        bug_type (str): Type of bug found (optional)
        pattern_coverage (int): Number of covered patterns (optional)
        branch_coverage (int): Number of covered branch conditions (optional)
        has_error (bool): Whether there was an error in test execution (new parameter)
        """
        self.visits += 1
        self.wins += reward
        
        # Add: track consecutive failures
        if not hasattr(self, 'consecutive_failures'):
            self.consecutive_failures = 0
            
        # Add: detect failures and errors, update failure count
        if has_error or reward < 0.1:  # consider very low reward as failure signal
            self.consecutive_failures += 1
            # Add: penalize accumulated rewards
            self.wins *= 0.9  # slight decay of accumulated rewards
        else:
            self.consecutive_failures = 0  # reset consecutive failure count
        
        # Update coverage data if provided
        if pattern_coverage is not None and hasattr(self, 'covered_patterns'):
            self.covered_patterns = pattern_coverage
        
        if branch_coverage is not None and hasattr(self, 'covered_branch_conditions'):
            self.covered_branch_conditions = branch_coverage
        
        # Update logic-specific rewards
        if bug_type:
            if bug_type.startswith("logical_"):
                # Higher reward for logical bugs
                self.logic_bug_rewards += 1.0
                self.bugs_found += 1
            elif bug_type.startswith("high_risk_"):
                # Reward for finding high-risk bugs
                self.high_risk_pattern_rewards += 0.8
                
        # Update logic coverage rewards if available from state
        if self.state:
            # Reward for covering logical patterns
            if hasattr(self.state, "covered_failures"):
                pattern_coverage = len(self.state.covered_failures) / 10.0  # Normalize
                self.failure_coverage_rewards += min(pattern_coverage, 1.0)
                
                # Update covered patterns - use actual set content from state
                if hasattr(self, 'covered_patterns'):
                    self.covered_patterns = self.state.covered_failures
                    
            # Reward for covering branch conditions
            if hasattr(self.state, "covered_branch_conditions"):
                branch_coverage = len(self.state.covered_branch_conditions) / 20.0  # Normalize
                self.failure_coverage_rewards += min(branch_coverage, 1.0)
                
                # Update covered branch conditions - use actual set content from state
                if hasattr(self, 'covered_branch_conditions'):
                    self.covered_branch_conditions = self.state.covered_branch_conditions

class FA_MCTS(EnhancedMCTSTestGenerator):
    """
    Logic-Aware Monte Carlo Tree Search for test generation.
    
    Enhances the base MCTS algorithm with logic-awareness to improve
    the detection of logical vulnerabilities in Java code.
    """
    
    def __init__(self, project_dir, prompt_dir, class_name, package_name, 
            initial_test_code, source_code, test_prompt, 
            f_model, failures, strategy_selector,
            max_iterations=20, exploration_weight=1.0,
            verify_bugs_mode="batch", focus_on_bugs=True, 
            f_weight=2.0, initial_coverage=0.0,
            bugs_threshold=100, project_type='maven'):
        """
        initialize Failure-Aware MCTS
        
        Parameters:
        project_dir (str): project directory
        prompt_dir (str): prompt directory
        class_name (str): class name
        package_name (str): package name
        initial_test_code (str): initial test code
        source_code (str): source code
        test_prompt (str): test generation prompt
        f_model (Extractor): failure model
        failures (list): detected failure scenarios
        strategy_selector (TestStrategySelector): strategy selector
        max_iterations (int): maximum iterations
        exploration_weight (float): exploration weight
        verify_bugs_mode (str): when to verify bugs (immediate/batch/none)
        focus_on_bugs (bool): whether to focus on finding bugs
        f_weight (float): reward weight for failure
        initial_coverage (float): initial code coverage
        bugs_threshold (int): failure threshold to terminate search
        """
        # initialize parent class
        super().__init__(
            project_dir=project_dir,
            prompt_dir=prompt_dir,
            class_name=class_name,
            package_name=package_name,
            initial_test_code=initial_test_code,
            source_code=source_code,
            test_prompt=test_prompt,
            max_iterations=max_iterations,
            exploration_weight=exploration_weight,
            verify_bugs_mode=verify_bugs_mode,
            focus_on_bugs=focus_on_bugs,
            project_type=project_type
        )
        
        # set logic-specific attributes
        self.f_model = f_model
        self.failures = failures
        self.strategy_selector = strategy_selector
        self.f_weight = f_weight
        self.bugs_threshold = bugs_threshold
        self.project_type = project_type
        
        # statistics and metrics
        self.bugs_found = 0
        self.verified_bug_methods = []
        self.best_logic_coverage = 0.0
        self.best_pattern_coverage = 0
        self.best_branch_coverage = 0
        self.current_coverage = initial_coverage
        
        # track current test state
        self.root_state = None
        self.best_state = None
        self.best_test = initial_test_code
        self.best_reward = 0.0
        
        # track algorithm execution history
        self.history = []
        
        # mapping of branch conditions and risk patterns to tests
        self.targeted_conditions = defaultdict(list)
        self.targeted_patterns = defaultdict(list)
        
        # Global compilation error tracking
        self.global_compilation_fix_attempts = 0
        self.compilation_error_history = []
        self.failed_fix_paths = set()  # Track paths that failed to fix compilation errors
        
        # Replace the initial TestState created by parent with FATestState
        if hasattr(self, 'root') and self.root:
            # Create new FATestState to replace the basic TestState
            failure_aware_initial_state = FATestState(
                test_code=initial_test_code,
                class_name=class_name,
                package_name=package_name,
                project_dir=project_dir,
                source_code=source_code,
                f_model=f_model,
                failures=failures,
                project_type=project_type
            )
            # Copy over any metrics from the basic state
            if hasattr(self.root.state, 'coverage'):
                failure_aware_initial_state.coverage = self.root.state.coverage
            if hasattr(self.root.state, 'execution_time'):
                failure_aware_initial_state.execution_time = self.root.state.execution_time
            if hasattr(self.root.state, 'executed'):
                failure_aware_initial_state.executed = self.root.state.executed
            # Replace the state in the root node
            self.root.state = failure_aware_initial_state
        
        # metrics for academic evaluation
        self.metrics = {
            "bug_types_found": set(),
            "boundary_conditions_covered": 0,
            "operations_covered": 0,
            "high_risk_patterns_covered": 0,
            "iterations_to_first_bug": None,
            "iterations_to_high_coverage": None,
            "total_test_methods": 0,
            "total_bug_tests": 0,
            "ucb_score_distribution": [],
            "strategy_effectiveness": defaultdict(lambda: {"used": 0, "bugs_found": 0})
        }
        
        # new: collect bugs for delayed verification
        self.potential_bugs = []
        self.potential_bug_signatures = set()
        self.unique_bugs = []  # used to record unique bugs
        
        # new: store test code with different coverage
        self.high_coverage_tests = {}
        
        logger.info(f"initialize Failure-Aware MCTS, f_weight={f_weight}, bugs_threshold={bugs_threshold}")

    # Add: extract dependency API context from test_prompt
    def _analyze_compilation_errors(self, errors):
        """
        Analyze compilation errors and provide specific fix suggestions
        
        Parameters:
        errors (list): List of compilation error messages
        
        Returns:
        list: List of tuples (error, suggestion)
        """
        analyzed = []
        
        for error in errors:
            suggestion = None
            error_lower = error.lower()
            
            # Analyze common compilation error patterns
            if "cannot find symbol" in error_lower:
                if "variable" in error_lower:
                    suggestion = "Declare the missing variable or check its spelling"
                elif "method" in error_lower:
                    suggestion = "Check method name spelling and ensure it exists in the class being tested"
                elif "class" in error_lower:
                    suggestion = "Add the missing import statement for this class"
                else:
                    suggestion = "Check spelling and ensure the symbol is properly imported or declared"
                    
            elif "incompatible types" in error_lower:
                suggestion = "Fix type mismatch - ensure the types match in assignment or method call"
                
            elif "package" in error_lower and "does not exist" in error_lower:
                suggestion = "Add the correct import statement for this package"
                
            elif "illegal start of expression" in error_lower:
                suggestion = "Check for missing parentheses, brackets, or semicolons before this line"
                
            elif "reached end of file while parsing" in error_lower:
                suggestion = "Check for missing closing brackets } or parentheses )"
                
            elif "unclosed comment" in error_lower:
                suggestion = "Close the comment block with */ or check for incomplete // comments"
                
            elif "; expected" in error_lower:
                suggestion = "Add missing semicolon at the end of the statement"
                
            elif "already defined" in error_lower:
                suggestion = "Remove duplicate variable or method declaration"
                
            elif "unreachable statement" in error_lower:
                suggestion = "Remove or fix code after return/break/continue statements"
                
            elif "missing return statement" in error_lower:
                suggestion = "Add a return statement with appropriate value"
                
            analyzed.append((error, suggestion))
            
        return analyzed

    def _extract_dependency_context_from_prompt(self):
        try:
            content = getattr(self, 'test_prompt', None) or ""
            if not content:
                return ""
            # find dependency API paragraph
            dep_start = content.find("4. DEPENDENCY API REFERENCES")
            if dep_start == -1:
                return ""
            # try to find GUIDELINES separator
            guide_marker = "\n-----------\n5. GUIDELINES"
            guide_pos = content.find(guide_marker, dep_start)
            if guide_pos != -1:
                dep_section = content[dep_start:guide_pos].strip()
                guide_section = content[guide_pos:].strip()
                return dep_section + "\n\n" + guide_section
            else:
                # if not found, return from dependency start to end
                return content[dep_start:].strip()
        except Exception:
            return ""

    def process_initial_state(self, initial_state):
        """
        Process the initial test state
        
        Parameters:
        initial_state (FATestState): Initial state
        
        Returns:
        FATestState: Processed initial state
        """
        logger.info("Processing initial test state")
        
        # If no initial state provided, create one
        if not initial_state:
            initial_state = FATestState(
                test_code=self.initial_test_code,
                class_name=self.class_name,
                package_name=self.package_name,
                project_dir=self.project_dir,
                source_code=self.source_code,
                f_model=self.f_model,
                failures=self.failures,
                project_type=getattr(self, 'project_type', 'maven')
            )
            
            # Evaluate the initial state
            initial_state.evaluate()
        
        from business_logic_analyzer import BusinessLogicAnalyzer
        analyzer = BusinessLogicAnalyzer()
        
        # Identify target methods to analyze (limit to most complex or error-prone methods)
        target_methods = self._identify_target_methods()

        # Initialize business logic analysis results
        business_logic_results = {
            "analyzed_methods": [],
            "potential_bugs": []
        }

        print("--------------------------------")
        print("Target Methods:")
        print(target_methods)
        print("--------------------------------")

        # Analyze each target method
        for method in target_methods:
            logger.info(f"Analyzing business logic for method: {method}")
            analysis = analyzer.analyze_code_for_logic_bugs(
                source_code=self.source_code,
                class_name=self.class_name,
                method_name=method
            )
            
            if "error" not in analysis:
                # print("--------------------------------")
                # print("Analyzed Method:")
                # print(method)
                # print("--------------------------------")

                # print("--------------------------------")
                # print("business_logic_results:")
                # print(business_logic_results)
                # print("--------------------------------")

                business_logic_results["analyzed_methods"].append(method)
                

                
                # Extract potential bugs with sufficient confidence
                if "potential_bugs" in analysis:
                    for bug in analysis["potential_bugs"]:
                        print("--------------------------------")
                        print("Bug:")
                        print(bug)
                        print("--------------------------------")
                        business_logic_results["potential_bugs"].append({
                            "method": method,
                            "type": bug.get("type", "unknown"),
                            "description": bug.get("description", ""),
                            "confidence": bug.get("confidence", 0.0),
                            "semantic_signals": bug.get("semantic_signals", {}),
                            "implementation_features": bug.get("implementation_features", {}),
                            "test_strategy": bug.get("test_strategy", "")
                        })
        
        
        
        
        # Log business logic analysis results
        if business_logic_results["potential_bugs"]:
            logger.info(f"Identified {len(business_logic_results['potential_bugs'])} potential business logic issues")
            for bug in business_logic_results["potential_bugs"]:
                logger.info(f"  - Method: {bug['method']}, Type: {bug['type']}, Confidence: {bug['confidence']:.2f}")

            # Store the business logic analysis in the state
            initial_state.business_logic_analysis = business_logic_results
        else:
            logger.info("No potential business logic issues identified")
        # Store as root state
        self.root_state = initial_state
        
        # Check for compilation errors in the initial state
        if hasattr(initial_state, "compilation_errors") and initial_state.compilation_errors:
            logger.warning(f"Initial state has compilation errors: {initial_state.compilation_errors[:2]}")
            # Ensure the compilation_errors attribute is properly set
            initial_state.compilation_errors = initial_state.compilation_errors
        
        # Set as current best state
        self.best_state = initial_state
        self.best_test = initial_state.test_code
        
        # Update metrics
        if initial_state.coverage > 0:
            self.current_coverage = initial_state.coverage
            
        if hasattr(initial_state, "covered_failures"):
            self.best_pattern_coverage = len(initial_state.covered_failures)
            
        if hasattr(initial_state, "covered_branch_conditions"):
            self.best_branch_coverage = len(initial_state.covered_branch_conditions)
        
        if initial_state.has_bugs:
            self.bugs_found = initial_state.count_logical_bugs()
            bug_methods = initial_state.get_logical_bug_finding_methods()
            
            # Add to verified bug methods
            for bug_method in bug_methods:
                if bug_method not in self.verified_bug_methods:
                    self.verified_bug_methods.append(bug_method)
                    
                    # Update metrics
                    bug_type = bug_method.get("bug_type", "unknown")
                    self.metrics["bug_types_found"].add(bug_type)
                    self.metrics["total_bug_tests"] += 1
        
        # Calculate initial reward
        initial_reward = self.calculate_failure_aware_reward(initial_state)
        self.best_reward = initial_reward
        
        logger.info(f"Initial state processed: coverage={self.current_coverage:.2f}%, " + 
                  f"bugs={self.bugs_found}, reward={initial_reward:.4f}")
        
        return initial_state
    
    def _identify_target_methods(self):
        """
        Identify target methods for business logic analysis
        
        Returns:
        list: Method names to analyze
        """
        target_methods = []
        
        # If logic model is available, use it to identify complex methods
        if self.f_model and hasattr(self.f_model, 'method_complexity'):
            # Sort methods by complexity
            complex_methods = sorted(
                self.f_model.method_complexity.items(),
                key=lambda x: x[1].get("cognitive", 0) + x[1].get("cyclomatic", 0),
                reverse=True
            )
            
            # Take top 3-5 most complex methods
            target_methods = [method for method, _ in complex_methods[:min(5, len(complex_methods))]]
        
        # Fall back to regex-based method extraction if needed
        if not target_methods:
            import re
            method_pattern = r'(?:public|private|protected)\s+(?:<.*?>)?\s*\w+\s+(\w+)\s*\([^)]*\)'
            matches = re.findall(method_pattern, self.source_code)
            
            # Filter out common utility methods
            exclude_patterns = ['equals', 'hashCode', 'toString', 'clone', 'finalize', 'main']
            target_methods = [m for m in matches if not any(ex == m for ex in exclude_patterns)]
            
            # Limit to reasonable number
            target_methods = target_methods[:min(5, len(target_methods))]
        
        # Add class name to potential methods list if it's a constructor
        if target_methods and self.class_name not in target_methods:
            target_methods.append(self.class_name)
        
        return target_methods
    
    def _analyze_business_logic(self, state, source_code=None, class_name=None):
        """
        Perform business logic analysis to enhance test generation
        
        Parameters:
        state (FATestState): Current test state
        source_code (str): Source code (optional)
        class_name (str): Class name (optional)
        
        Returns:
        dict: Logic insights for test generation
        """
        if not hasattr(self, 'business_logic_analyzer'):
            # Import here to avoid circular imports
            from business_logic_analyzer import BusinessLogicAnalyzer
            self.business_logic_analyzer = BusinessLogicAnalyzer()
        
        # Use source_code and class_name from parameters or instance variables
        source_code = source_code or self.source_code
        class_name = class_name or self.class_name
        
        # Get target methods to analyze
        target_methods = self._get_target_methods()
        logic_insights = {}
        
        for method in target_methods:
            # Skip methods that have already been analyzed
            if hasattr(state, 'logic_insights') and method in state.logic_insights:
                logic_insights[method] = state.logic_insights[method]
                continue
            
            # Analyze method's business logic
            try:
                logic_analysis = self.business_logic_analyzer.analyze_code_for_logic_bugs(
                    source_code, class_name, method)
                
                # Only add results with sufficient confidence
                if logic_analysis.get("confidence", 0.0) >= 0.7:
                    logic_insights[method] = logic_analysis
                    logger.info(f"Found potential logic bug in method {method} with confidence {logic_analysis['confidence']:.2f}")
            except Exception as e:
                logger.error(f"Error analyzing method {method}: {str(e)}")
        
        # Update state with business logic insights
        state.logic_insights = logic_insights
        
        # Use insights to adjust test generation strategy
        if logic_insights:
            # There are potential logic bugs identified, adjust test generation
            return self._adjust_test_generation_strategy(state, logic_insights)
        else:
            return state

    def _get_target_methods(self):
        """Get target methods for business logic analysis"""
        import re
        
        # Default to methods extracted from source code
        methods = []
        
        # Extract method names from source code
        method_pattern = r'(?:public|private|protected)?\s+(?:static\s+)?(?:final\s+)?(?:\w+(?:<[^>]+>)?)\s+(\w+)\s*\('
        method_matches = re.finditer(method_pattern, self.source_code)
        
        for match in method_matches:
            method_name = match.group(1)
            # Skip constructor and common utility methods
            if method_name != self.class_name and method_name not in ['toString', 'hashCode', 'equals']:
                methods.append(method_name)
        
        # Prioritize public methods
        public_methods = []
        for method in methods:
            public_pattern = r'public\s+(?:static\s+)?(?:final\s+)?(?:\w+(?:<[^>]+>)?)\s+' + re.escape(method) + r'\s*\('
            if re.search(public_pattern, self.source_code):
                public_methods.append(method)
        
        # If we have too many methods, focus on public ones
        if len(methods) > 5 and public_methods:
            return public_methods
        
        return methods

    def _adjust_test_generation_strategy(self, state, logic_insights):
        """
        Adjust test generation strategy based on business logic insights
        
        Parameters:
        state (FATestState): Current test state
        logic_insights (dict): Business logic insights
        
        Returns:
        FATestState: Updated state with strategy adjustments
        """
        # Add target methods and insights to state for test generation
        state.logic_bug_targets = []
        
        for method, analysis in logic_insights.items():
            for bug in analysis.get("potential_bugs", []):
                state.logic_bug_targets.append({
                    "method": method,
                    "bug_description": bug.get("description", ""),
                    "confidence": bug.get("confidence", 0.0),
                    "intended_behavior": analysis.get("llm_analysis", {}).get("intended_behavior", ""),
                    "actual_behavior": analysis.get("llm_analysis", {}).get("actual_behavior", "")
                })
        
        # Set state flags to help guide test generation
        state.has_potential_logic_bugs = len(state.logic_bug_targets) > 0
        
        # Log analysis results
        logger.info(f"Identified {len(state.logic_bug_targets)} potential logic bug targets")
        
        return state


    def run_search(self):
        """
        运行MCTS搜索算法
        
        Returns:
        tuple: (best_test_code, best_coverage)
        """
        logger.info(f"开始Logic-Aware MCTS搜索，迭代次数: {self.max_iterations}")
        
        # 存储开始时间用于性能指标
        self.start_time = time.time()
        
        # 初始化所有状态的跟踪列表
        self.all_states = []
        
        # 如果尚未处理，处理初始状态
        if not self.root_state:
            self.root_state = self.process_initial_state(None)
        
        # 添加初始状态到跟踪列表
        if self.root_state:
            self.all_states.append(self.root_state)
        
        # NEW: Add business logic analysis to initial state
        # This will enhance the test generation with awareness of potential logic bugs
        # self.root_state = self._analyze_business_logic(self.root_state)

        # 创建根节点
        self.root = FA_MCTSNode(self.root_state)  # 存储为实例属性
        
        # 主MCTS循环
        for iteration in range(1, self.max_iterations + 1):
            self.current_iteration = iteration  # track the current iteration
            logger.info(f"iteration {iteration}/{self.max_iterations}")
            
            try:
                # 1. select - use the stored root node
                selected_node = self.selection(self.root)
                
                # 2. expand
                if selected_node.is_fully_expanded():
                    expanded_node = selected_node
                else:
                    expanded_node = self.expansion(selected_node)
                    # if a new state is created, add it to the tracking list
                    if expanded_node != selected_node and expanded_node.state:
                        self.all_states.append(expanded_node.state)
                
                # 3. simulation
                reward = self.simulation(expanded_node)
                
                # 4. backpropagation
                self.backpropagation(expanded_node, reward)
                
                # update the best test
                if reward > self.best_reward:
                    self.update_best_tests(expanded_node.state, reward, iteration)
                
                # record history - use the most promising child node instead of the last expanded node
                best_node = self.root.best_child(
                    exploration_weight=self.exploration_weight,
                    f_weight=self.f_weight
                )
                self.record_history(best_node, iteration, reward)
                
                # check the termination condition
                if self.check_termination(iteration):
                    logger.info(f"iteration {iteration} satisfies the termination condition")
                    break
                
            except Exception as e:
                logger.error(f"iteration {iteration} error: {str(e)}")
                logger.error(traceback.format_exc())
        
        # save the metrics for academic evaluation
        self.save_metrics()
        
        # after MCTS search, verify all collected potential bugs
        logger.info(f"MCTS search completed, starting to verify {len(self.potential_bugs)} potential bugs")
        logger.info(f"collected {len(self.all_states)} test states")
        self.verified_bug_methods = self.verify_all_potential_bugs()
        
        # ===== key modification: generate test summary after verifying bugs =====
        # save the test summary to a JSON file
        self.save_test_summary()
        
        # generate the integrated test code with all verified bugs
        logger.info("generating the integrated test code with all verified bugs...")
        integrated_test_code = self.generate_integrated_test_code()
        
        # if there is an integrated test code, use it as the final result
        if integrated_test_code and integrated_test_code != self.best_test:
            logger.info("using the integrated test code as the final result")
            final_test_code = integrated_test_code
        else:
            logger.info("using the best test code as the final result(no bug integration)")
            final_test_code = self.best_test
        
        logger.info(f"MCTS search completed: best coverage={self.current_coverage:.2f}%, " +
                f"found bugs={len([m for m in self.verified_bug_methods if m.get('is_real_bug', False)])}")
        
        return final_test_code, self.current_coverage


    def verify_all_potential_bugs(self):
        """
        verify all collected potential bugs at once
        
        Returns:
        list: verified bug methods list
        """
        from bug_verifier import BugVerifier
        
        # create the bug verifier
        verifier = BugVerifier(self.source_code, self.class_name, self.package_name)
        
        # group potential bugs by method name
        bugs_by_method = {}
        for bug in self.potential_bugs:
            method_name = bug.get("test_method", "unknown")
            if method_name not in bugs_by_method:
                bugs_by_method[method_name] = []
            
            # only add unverified bugs
            if not bug.get("verified", False):
                # generate a robust bug signature for each bug (if not already done)
                if not bug.get("bug_signature"):
                    bug["bug_signature"] = self._create_robust_bug_signature(bug)
                    
                bugs_by_method[method_name].append(bug)
        
        if not bugs_by_method:
            logger.info("no bugs to verify")
            return []
            
        logger.info(f"starting to verify {len(bugs_by_method)} potential bugs in {len(bugs_by_method)} test methods")
        
        # prepare the method list for verification
        methods_to_verify = []
        
        for method_name, bugs in bugs_by_method.items():
            # if there are no bugs to verify, skip
            if not bugs:
                continue
                
            # use the method code extracted in the simulation process
            # if multiple bugs correspond to the same method, use the method code of the first bug with code
            method_code = None
            for bug in bugs:
                if bug.get("method_code"):
                    method_code = bug.get("method_code")
                    logger.info(f"using the method code extracted in the simulation process: {method_name}")
                    break
            
            # if there is no method code, try to extract it from multiple sources
            if not method_code:
                # TODO: try to extract code from multiple sources...
               
                pass
            
            # merge the bug information of the same method
            bug_descriptions = []
            for bug in bugs:
                bug_type = bug.get("bug_type", "unknown")
                error = bug.get("error", "")
                
                # simplify the error information
                if len(error) > 100:
                    error = error[:100] + "..."
                    
                bug_descriptions.append(f"{bug_type}: {error}")
            
            # create the method verification information, using the robust bug signature
            method_info = {
                "method_name": method_name,
                "code": method_code,
                "bug_info": bugs,
                "bug_descriptions": bug_descriptions,
                "bug_signature": bugs[0].get("bug_signature")  # use the signature of the first bug
            }
            
            methods_to_verify.append(method_info)
        
        # verify all methods
        verified_methods = verifier.verify_bugs(methods_to_verify)
        
        # save the original verification results
        self.original_verified_methods = verified_methods.copy()
        
        # add the robust signature to the verified methods
        for method in verified_methods:
            if not method.get("bug_signature"):
                method["bug_signature"] = self._create_robust_bug_signature(method)
        
        # ensure the verification results correctly reflect the real/false positive status
        for method in verified_methods:
            method_name = method.get("method_name", "")
            is_real_bug = method.get("is_real_bug", False)
            logger.info(f"verification result: method {method_name}, is_real_bug={is_real_bug}")
        
        # calculate and record the verification results
        real_bugs = [m for m in verified_methods if m.get("is_real_bug", False)]
        false_positives = [m for m in verified_methods if not m.get("is_real_bug", False)]
        
        # explicitly set the verification statistics
        self.real_bugs_count = len(real_bugs)
        self.false_positives_count = len(false_positives) 
        self.total_verified_methods = len(verified_methods)
        
        # update the logical bug count - here we only count the real bugs
        self.bugs_found = self.real_bugs_count
        
        logger.info(f"verification completed: found {len(real_bugs)} real bugs, {len(false_positives)} false positives, from {len(verified_methods)} test methods")
        
        return verified_methods


    def process_bug_findings(self, state, iteration):
        """
        process the bug findings in the state
        
        Parameters:
        state (FATestState): test state
        iteration (int): current iteration
        """
        # skip the state without bugs
        if not state or not hasattr(state, 'has_bugs') or not state.has_bugs:
            return
            
        # get the logical bug methods
        bug_methods = state.get_logical_bug_finding_methods()
        
        if not bug_methods:
            logger.warning("State reports logical bugs but get_logical_bug_finding_methods returned empty list")
            return
            
        logger.info(f"Found {len(bug_methods)} logical bug methods at iteration {iteration}")
        
        # track the new found bugs
        found_new_bugs = False
        verified_bug_count = 0
        total_detected_bugs = len(bug_methods)
        
        # used to track the unique bug methods found in the current iteration
        current_iteration_bug_methods = set()
        current_iteration_verified_bug_methods = set()
            
        # process each bug method
        for bug_method in bug_methods:
            method_name = bug_method.get("method_name", "")
            bug_type = bug_method.get("bug_type", "unknown")
            bug_category = bug_method.get("bug_category", "general")
            
            # add the iteration information
            bug_method["found_in_iteration"] = iteration
            
            # skip the non-logical bugs
            if bug_category != "logical":
                continue
            
            # generate the robust bug signature
            if not bug_method.get("bug_signature"):
                bug_method["bug_signature"] = self._create_robust_bug_signature(bug_method)
                
            bug_signature = bug_method["bug_signature"]
            
            # add to the set of bugs found in the current iteration
            current_iteration_bug_methods.add(bug_signature)
                    
            # check if it is a new bug type
            if bug_type not in self.metrics["bug_types"]:
                # record the iteration when the first logical bug is found
                if self.metrics["iterations_to_first_bug"] is None:
                    self.metrics["iterations_to_first_bug"] = iteration
                
                # add to the bug type set
                self.metrics["bug_types"].add(bug_type)
                logger.info(f"Found new logical bug type: {bug_type}")
            
            # check if it is a new bug discovery - use the unique signature comparison
            method_exists = any(m.get("bug_signature", "") == bug_signature for m in self.verified_bug_methods)
            is_new_bug = not method_exists
            
            # extract the complete test method code
            if not bug_method.get("method_code") and state.test_code:
                # try to extract the complete method from the test code
                method_code = self._extract_method_from_test_code(state.test_code, method_name)
                if method_code:
                    bug_method["method_code"] = method_code
                    bug_method["found_in_iteration"] = iteration_number
                    logger.info(f"successfully extracted the method code: {method_name}")
            
            # verify the bug - even if it has been verified, still record the iteration number
            try:
                # if the bug is not verified, verify it
                if not bug_method.get("verified", False):
                    verifier = BugVerifier(self.source_code, self.class_name, self.package_name)
                    verified_result = verifier.verify_bugs([bug_method])
                    
                    # if the verification returns a result, use the verification information
                    if verified_result and len(verified_result) > 0:
                        # update the bug_method content, keep the iteration number and method code
                        verified_bug = verified_result[0]
                        iteration_number = bug_method.get("found_in_iteration")
                        method_code = bug_method.get("method_code", "")
                        bug_signature = bug_method.get("bug_signature", "")
                        
                        for key, value in verified_bug.items():
                            bug_method[key] = value
                            
                        # restore the iteration number, method code and bug signature
                        bug_method["found_in_iteration"] = iteration_number
                        if method_code:
                            bug_method["method_code"] = method_code
                        if bug_signature:
                            bug_method["bug_signature"] = bug_signature
                    else:
                        # if the verification returns no result, mark it as verified
                        bug_method["verified"] = True
                        bug_method["is_real_bug"] = bug_method.get("is_real_bug", False)
                        logger.warning(f"Bug verification returned no results for {method_name}")
                else:
                    logger.info(f"Bug already verified: {method_name}")
                    # ensure that even verified bugs have an iteration number
                    if "found_in_iteration" not in bug_method:
                        bug_method["found_in_iteration"] = iteration
            except Exception as e:
                logger.error(f"Error verifying bug {method_name}: {str(e)}")
                logger.error(traceback.format_exc())
                # when there is an error, mark it as verified but keep the original is_real_bug value
                bug_method["verified"] = True
            
            # add to the verified bug methods (if it is a new bug)
            if is_new_bug:
                self.verified_bug_methods.append(bug_method)
                self.bugs_found += 1
                found_new_bugs = True
                
                # update the metrics
                self.metrics["total_bug_tests"] += 1
                
                # update the strategy effectiveness
                if hasattr(state, 'metadata') and state.metadata and "action" in state.metadata and "strategy" in state.metadata["action"]:
                    strategy = state.metadata["action"]["strategy"]
                    self.metrics["strategy_effectiveness"][strategy]["bugs_found"] += 1
                
                logger.info(f"Added new logical bug method: {method_name} (type: {bug_type}, is_real_bug: {bug_method.get('is_real_bug', False)})")
            
            # calculate the verified real bugs
            if bug_method.get("verified", False) and bug_method.get("is_real_bug", False):
                verified_bug_count += 1
                current_iteration_verified_bug_methods.add(bug_signature)
        
        # record the number of unique bugs found in the current iteration
        unique_bugs_in_iteration = len(current_iteration_bug_methods)
        unique_verified_bugs_in_iteration = len(current_iteration_verified_bug_methods)
        
        # update the bug trend - ensure using the number of unique bugs found in the current iteration instead of repeated calculation
        self._update_bug_trend(iteration, unique_bugs_in_iteration, unique_verified_bugs_in_iteration)
        
        # update the bug count in the state, used for reward calculation
        state.unique_bugs_count = unique_bugs_in_iteration
        state.unique_verified_bugs_count = unique_verified_bugs_in_iteration
        
        # if a new bug is found and this state is not the best state, consider keeping it
        if found_new_bugs and state != self.best_state:
            # re-calculate the reward considering the impact of the new bug
            reward = self.calculate_failure_aware_reward(state)
            
            # if the version with bugs has a higher reward than the current best, update the best version
            if reward > self.best_reward:
                self.update_best_tests(state, reward, iteration)

    def _extract_method_from_test_code(self, test_code, method_name):
        """
        extract the code of the specified method from the complete test code
        
        Parameters:
        test_code (str): complete test code
        method_name (str): method name
        
        Returns:
        str: the extracted method code, if not found, return an empty string
        """
        import re
        try:
            # match the method definition and the entire method body, considering different access modifiers and return types
            pattern = r'(public|private|protected)?\s+(?:static\s+)?(?:final\s+)?(?:[\w\<\>\[\]]+\s+)?' + re.escape(method_name) + r'\s*\([^\)]*\)\s*(?:throws\s+[\w\.,\s]+)?\s*\{(?:[^{}]|(?:\{(?:[^{}]|(?:\{(?:[^{}]|(?:\{[^{}]*\}))*\}))*\}))*\}'
            
            match = re.search(pattern, test_code)
            if match:
                return match.group(0)
            
            return ""
        except Exception as e:
            logger.error(f"error extracting the method: {str(e)}")
            return ""
    
    def _rename_variables(self, code, used_vars, suffix):
        """
        more comprehensive variable renaming, avoiding conflicts
        
        Parameters:
        code (str): source code
        used_vars (set): used variable names
        suffix (int): method suffix
        
        Returns:
        tuple: (modified code, new used variable names)
        """
        import re
        new_used_vars = set()
        modified_code = code
        
        # reserved words and common types, should not be mistaken for variables
        java_keywords = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "false",
            "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "true", "try", "void", "volatile", "while",
            "String", "Integer", "Double", "Float", "Long", "Boolean", "Character", "Byte", "Short",
            "Object", "Class", "System", "Exception", "RuntimeException", "Throwable", "Error",
            "List", "Map", "Set", "Collection", "Arrays", "ArrayList", "HashMap", "HashSet",
            "StringBuilder", "StringBuffer", "Math", "Thread", "Runnable"
        }
        
        # add a unique prefix to each test method
        method_prefix = f"m{suffix}_"
        
        # expand the matching pattern to capture more variable declaration scenarios
        var_patterns = [
            # basic variable assignment
            r'(\w+(?:<[^>]+>)?)\s+(\w+)\s*=',
            
            # variable declaration without initialization
            r'(\w+(?:<[^>]+>)?)\s+(\w+)\s*;',
            
            # for-each loop
            r'for\s*\(\s*(\w+(?:<[^>]+>)?)\s+(\w+)\s*:',
            
            # normal for loop
            r'for\s*\(\s*(\w+(?:<[^>]+>)?)\s+(\w+)\s*=',
            
            # catch statement
            r'catch\s*\(\s*(\w+(?:<[^>]+>)?)\s+(\w+)\s*\)',
            
            # method parameters
            r'(?:public|private|protected)?\s*(?:static)?\s*(?:final)?\s*(\w+(?:<[^>]+>)?)\s+(\w+)\s*\(',
            
            # add support for FailMapper expression parameters
            r'(?:\(|,)\s*(\w+)\s*->'
        ]
        
        # collect all variables and their types
        all_vars = []
        for pattern in var_patterns:
            matches = re.finditer(pattern, code)
            for match in matches:
                try:
                    # for most patterns, the variable type is in group 1, and the variable name is in group 2
                    if len(match.groups()) > 1:
                        var_type = match.group(1)
                        var_name = match.group(2)
                    else:
                        # for FailMapper expressions, only the variable name is in group 1
                        var_type = "unknown"
                        var_name = match.group(1)
                    
                    # skip the keywords and basic types
                    if var_name in java_keywords:
                        continue
                        
                    all_vars.append((var_type, var_name))
                except Exception as e:
                    logger.debug(f"error processing variable matching: {str(e)}")
                    continue
        
        # create the variable renaming mapping - add a specific prefix to all variables
        var_map = {}
        for var_type, var_name in all_vars:
            # add the method specific prefix to all variables
            new_name = f"{method_prefix}{var_name}"
            var_map[var_name] = new_name
            new_used_vars.add(new_name)
        
        # systematically replace all variable names
        # sort the variable names by length from longest to shortest to prevent partial replacement issues
        sorted_vars = sorted(var_map.items(), key=lambda x: len(x[0]), reverse=True)
        
        for old_name, new_name in sorted_vars:
            # use regex to replace the variable names, ensuring only complete identifiers are replaced
            modified_code = re.sub(
                r'\b' + re.escape(old_name) + r'\b',
                new_name,
                modified_code
            )
        
        # check and rename the commonly used test framework methods to avoid conflicts
        helper_method_patterns = [
            (r'void\s+when\s*\(', f'void when_{suffix}('),
            (r'void\s+then\s*\(', f'void then_{suffix}('),
            (r'void\s+given\s*\(', f'void given_{suffix}('),
            (r'(?:void|boolean)\s+assert\w+\s*\(', f'\\g<0>{suffix}')
        ]
        
        for pattern, replacement in helper_method_patterns:
            modified_code = re.sub(pattern, replacement, modified_code)
        
        # modify the calls within the methods
        for pattern, _ in helper_method_patterns:
            base_name = re.search(r'(?:void|boolean)\s+(\w+)', pattern)
            if base_name:
                method_name = base_name.group(1)
                modified_code = re.sub(
                    r'\b' + re.escape(method_name) + r'\s*\(',
                    f"{method_name}_{suffix}(",
                    modified_code
                )
        
        # replace possible FailMapper expressions
        failmapper_pattern = r'(?:->\s*\{|\(\)\s*->\s*\{)'
        if re.search(failmapper_pattern, modified_code):
            # extract the FailMapper internal variables and replace them
            failmapper_pattern = re.finditer(r'->\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}', modified_code)
            for failmapper_match in failmapper_pattern:
                failmapper_body = failmapper_match.group(1)
                failmapper_vars = re.finditer(r'\b(\w+)\b', failmapper_body)
                for var_match in failmapper_vars:
                    var_name = var_match.group(1)
                    if var_name in var_map:
                        failmapper_body = re.sub(
                            r'\b' + re.escape(var_name) + r'\b',
                            var_map[var_name],
                            failmapper_body
                        )
                # replace the modified FailMapper body
                modified_code = modified_code.replace(
                    failmapper_match.group(0),
                    "-> {" + failmapper_body + "}"
                )
        
        return modified_code, new_used_vars

    def _add_missing_helper_methods(self, code):
        """
        find and add the missing helper methods
        
        Parameters:
        code (str): integration test code
        
        Returns:
        str: updated code
        """
        import re
        
        # detect the helper method calls in the code
        helper_calls = set()
        helper_patterns = [
            r'(\w+)_(\d+)\s*\((?:\s*[\w\[\],.<>]+\s+\w+\s*(?:,)?)*\s*\)',  # 带参数的方法调用
            r'assert\w+_(\d+)\s*\(',  # 带后缀的assert方法
        ]
        
        for pattern in helper_patterns:
            matches = re.finditer(pattern, code)
            for match in matches:
                if match.group(0).startswith("assert"):
                    # process the assert method
                    helper_calls.add(f"assert{match.group(1)}")
                else:
                    # process the other helper methods
                    helper_calls.add(f"{match.group(1)}_{match.group(2)}")
        
        # define the helper method templates
        helper_templates = {
            "when": """
        // helper method: when
        private void when_{suffix}(Object obj) {
            // test helper method
        }
        
        private void when_{suffix}(double value) {
            // test helper method
        }
        """,
            "then": """
        // helper method: then
        private void then_{suffix}(Object obj) {
            // test helper method
        }
        
        private void then_{suffix}(double value) {
            // test helper method
        }
        """,
            "given": """
        // helper method: given
        private void given_{suffix}(Object obj) {
            // test helper method
        }
        
        private void given_{suffix}(double value) {
            // test helper method
        }
        """,
            "assert": """
        // helper method: assert
        private <T extends Throwable> void assertThrows_{suffix}(Class<T> expectedType, Runnable code, String message) {
            try {
                code.run();
                fail("Expected exception: " + expectedType.getName() + " but nothing was thrown");
            } catch (Throwable t) {
                if (!expectedType.isInstance(t)) {
                    fail(message + " - Expected: <" + expectedType.getName() + "> but was: <" + t.getClass().getName() + ">");
                }
            }
        }
        """
        }
        
        # build the missing helper methods
        missing_methods = []
        for helper in helper_calls:
            for base_name, template in helper_templates.items():
                if helper.startswith(base_name):
                    suffix = helper.split('_')[-1]
                    missing_methods.append(template.format(suffix=suffix))
                    break
        
        # add the missing methods to the end of the class
        if missing_methods:
            class_end = code.rfind('}')
            if class_end != -1:
                code = (
                    code[:class_end] + 
                    "\n    // ===== automatically generated helper methods ===== \n" +
                    "".join(missing_methods) +
                    code[class_end:]
                )
        
        return code

    def _update_bug_trend(self, iteration, detected_bugs, verified_bugs):
        """
        Update the bug trend records with cumulative bug counts
        
        Parameters:
        iteration (int): Current iteration
        detected_bugs (int or list): Number of detected bugs or list of bug methods in current iteration
        verified_bugs (int or list): Number of verified bugs or list of verified bug methods in current iteration
        """
        # Make sure bug_trend exists
        if not hasattr(self, "bug_trend"):
            self.bug_trend = []
            self.unique_detected_bug_signatures = set()
            self.unique_verified_bug_signatures = set()
        
        # Extract current iteration bug methods from history
        current_detected_methods = []
        current_verified_methods = []
        
        # Find the history entry for this iteration
        history_entry = None
        for entry in self.history:
            if entry.get("iteration") == iteration:
                history_entry = entry
                break
        
        # Process the history entry if found
        if history_entry:
            # If we have bug_details, use those for more accurate tracking
            if "bug_details" in history_entry and isinstance(history_entry["bug_details"], list):
                current_detected_methods = []
                current_verified_methods = []
                
                for bug in history_entry["bug_details"]:
                    # use the new robust signature function to create a unique bug signature
                    bug_info = {
                        "method_name": bug.get("method", "unknown"),
                        "bug_type": bug.get("type", "unknown"),
                        "error": bug.get("description", ""),
                        "found_in_iteration": iteration
                    }
                    
                    bug_signature = self._create_robust_bug_signature(bug_info)
                    
                    current_detected_methods.append(bug_signature)
                    if bug.get("verified", False) and bug.get("is_real_bug", False):
                        current_verified_methods.append(bug_signature)
            else:
                # Handle detected_bugs - for scenarios without detailed bug_details
                if "detected_bugs" in history_entry:
                    if isinstance(history_entry["detected_bugs"], list):
                        for bug_name in history_entry["detected_bugs"]:
                            # create the bug information and generate the signature
                            bug_info = {
                                "method_name": bug_name,
                                "bug_type": "unknown",
                                "found_in_iteration": iteration
                            }
                            current_detected_methods.append(self._create_robust_bug_signature(bug_info))
                    elif isinstance(history_entry["detected_bugs"], (int, float)) and history_entry["detected_bugs"] > 0:
                        for i in range(int(history_entry["detected_bugs"])):
                            bug_info = {
                                "method_name": f"bug_method_{iteration}_{i}",
                                "bug_type": "unknown",
                                "found_in_iteration": iteration
                            }
                            current_detected_methods.append(self._create_robust_bug_signature(bug_info))
                
                # Handle verified_bugs
                if "verified_bugs" in history_entry:
                    if isinstance(history_entry["verified_bugs"], list):
                        for bug_name in history_entry["verified_bugs"]:
                            bug_info = {
                                "method_name": bug_name,
                                "bug_type": "unknown", 
                                "found_in_iteration": iteration
                            }
                            current_verified_methods.append(self._create_robust_bug_signature(bug_info))
                    elif isinstance(history_entry["verified_bugs"], (int, float)) and history_entry["verified_bugs"] > 0:
                        for i in range(int(history_entry["verified_bugs"])):
                            bug_info = {
                                "method_name": f"verified_method_{iteration}_{i}",
                                "bug_type": "unknown",
                                "found_in_iteration": iteration
                            }
                            current_verified_methods.append(self._create_robust_bug_signature(bug_info))
        else:
            # If no history entry found, use the direct parameters
            logger.info(f"No history entry found for iteration {iteration}, using direct parameters")
            
            # Handle detected_bugs parameter - could be list or number
            if isinstance(detected_bugs, list):
                for i, bug in enumerate(detected_bugs):
                    bug_name = bug if isinstance(bug, str) else f"bug_method_{iteration}_{i}"
                    bug_info = {
                        "method_name": bug_name,
                        "bug_type": "unknown",
                        "found_in_iteration": iteration
                    }
                    current_detected_methods.append(self._create_robust_bug_signature(bug_info))
            elif detected_bugs > 0:
                for i in range(detected_bugs):
                    bug_info = {
                        "method_name": f"bug_method_{iteration}_{i}",
                        "bug_type": "unknown",
                        "found_in_iteration": iteration
                    }
                    current_detected_methods.append(self._create_robust_bug_signature(bug_info))
            
            # Handle verified_bugs parameter - could be list or number
            if isinstance(verified_bugs, list):
                for i, bug in enumerate(verified_bugs):
                    bug_name = bug if isinstance(bug, str) else f"verified_method_{iteration}_{i}"
                    bug_info = {
                        "method_name": bug_name,
                        "bug_type": "unknown",
                        "found_in_iteration": iteration
                    }
                    current_verified_methods.append(self._create_robust_bug_signature(bug_info))
            elif verified_bugs > 0:
                for i in range(verified_bugs):
                    bug_info = {
                        "method_name": f"verified_method_{iteration}_{i}",
                        "bug_type": "unknown",
                        "found_in_iteration": iteration
                    }
                    current_verified_methods.append(self._create_robust_bug_signature(bug_info))
        
        # Update the unique bug sets
        self.unique_detected_bug_signatures.update(current_detected_methods)
        self.unique_verified_bug_signatures.update(current_verified_methods)
        
        # Calculate cumulative counts
        cumulative_detected = len(self.unique_detected_bug_signatures)
        cumulative_verified = len(self.unique_verified_bug_signatures)
        
        # Convert detected_bugs/verified_bugs to integers if they're lists
        detected_bugs_count = len(detected_bugs) if isinstance(detected_bugs, list) else detected_bugs
        verified_bugs_count = len(verified_bugs) if isinstance(verified_bugs, list) else verified_bugs
        
        # Add a new trend point with both current and cumulative data
        self.bug_trend.append({
            "iteration": iteration,
            "detected_bugs": detected_bugs_count,              # Bugs found in this iteration
            "verified_bugs": verified_bugs_count,              # Verified bugs in this iteration
            "cumulative_detected_bugs": cumulative_detected,   # Total unique bugs found so far
            "cumulative_verified_bugs": cumulative_verified    # Total unique verified bugs so far
        })
        
        # Add bug trend to metrics
        self.metrics["bug_trend"] = self.bug_trend
        
        logger.info(f"Updated bug trend - iteration {iteration}: " +
                f"detected={detected_bugs_count}, verified={verified_bugs_count}, " +
                f"cumulative detected={cumulative_detected}, cumulative verified={cumulative_verified}")
    

    def _generate_bug_trend(self):
        """
        generate the bug trend data from the history, used for test summary
        
        Returns:
        list: bug trend data list
        """
        # if there is no history, return an empty list
        if not hasattr(self, "history") or not self.history:
            return []
        
        # get the number of real bugs in the original verified methods
        real_bugs_count = 0
        verified_bug_methods = []
        
        # first get the real bugs from the original verified results
        if hasattr(self, "original_verified_methods") and self.original_verified_methods:
            for bug in self.original_verified_methods:
                if bug.get("is_real_bug", False):
                    method_name = bug.get("method_name", "")
                    if method_name and method_name not in verified_bug_methods:
                        verified_bug_methods.append(method_name)
        
        # also get the real bugs from the verified_bug_methods
        if hasattr(self, "verified_bug_methods") and self.verified_bug_methods:
            for bug in self.verified_bug_methods:
                if bug.get("is_real_bug", False):
                    method_name = bug.get("method_name", "")
                    if method_name and method_name not in verified_bug_methods:
                        verified_bug_methods.append(method_name)
        
        real_bugs_count = len(verified_bug_methods)
        logger.info(f"found {real_bugs_count} real bugs for the trend graph")
        
        # initialize the trend data and cumulative bug tracking
        bug_trend = []
        unique_detected_bugs = set()
        unique_verified_bugs = set()
        
        # create the trend data point for each iteration
        for entry in sorted(self.history, key=lambda x: x.get("iteration", 0)):
            iteration = entry.get("iteration", 0)
            
            # get the unique set of bugs detected in the current iteration (deduplicated)
            detected_bugs_set = set()
            if "detected_bugs" in entry and isinstance(entry["detected_bugs"], list):
                detected_bugs_set.update(entry["detected_bugs"])
            elif "bugs_found" in entry and entry["bugs_found"] > 0:
                # if there is only a number indicating the number of bugs, use a placeholder
                for i in range(entry["bugs_found"]):
                    detected_bugs_set.add(f"bug_{iteration}_{i}")
            
            # get the unique set of bugs verified in the current iteration (deduplicated)
            verified_bugs_set = set()
            if "verified_bugs" in entry and isinstance(entry["verified_bugs"], list):
                verified_bugs_set.update(entry["verified_bugs"])
            
            # if there is a bug_details field, also extract the information from it
            if "bug_details" in entry and isinstance(entry["bug_details"], list):
                for bug in entry["bug_details"]:
                    method = bug.get("method", "")
                    if method:
                        detected_bugs_set.add(method)
                        if bug.get("verified", False) and bug.get("is_real_bug", False):
                            verified_bugs_set.add(method)
            
            # the number of unique bugs in the current iteration
            detected_bugs_count = len(detected_bugs_set)
            verified_bugs_count = len(verified_bugs_set)
            
            # update the cumulative unique bug set
            unique_detected_bugs.update(detected_bugs_set)
            unique_verified_bugs.update(verified_bugs_set)
            
            # create the trend point with cumulative statistics
            current_entry = {
                "iteration": iteration,
                "detected_bugs": detected_bugs_count,  # the number of unique bugs detected in the current iteration
                "verified_bugs": verified_bugs_count,  # the number of unique bugs verified in the current iteration
                "cumulative_detected_bugs": len(unique_detected_bugs),  # the cumulative number of unique bugs detected
                "cumulative_verified_bugs": len(unique_verified_bugs)   # the cumulative number of unique verified bugs
            }
            
            bug_trend.append(current_entry)
        
        # if no trend data is created, add an empty trend point to prevent errors
        if not bug_trend:
            bug_trend.append({
                "iteration": 1,
                "detected_bugs": 0,
                "verified_bugs": 0,
                "cumulative_detected_bugs": 0,
                "cumulative_verified_bugs": 0
            })
        
        return bug_trend

    def _generate_coverage_trend(self):
        """
        generate the coverage trend data from the history, used for test summary
        
        Returns:
        list: coverage trend data list
        """
        # if there is no history, return an empty list
        if not hasattr(self, "history") or not self.history:
            return []
        
        # extract the coverage information from the history
        coverage_trend = []
        unique_bug_signatures = set()
        unique_verified_bug_signatures = set()
        
        for i, entry in enumerate(sorted(self.history, key=lambda x: x.get("iteration", 0))):
            iteration = entry.get("iteration", i+1)
            
            # extract the bugs detected in the current iteration
            current_iteration_bugs = set()
            current_iteration_verified_bugs = set()
            
            # process the information in the bug_details field
            if "bug_details" in entry and isinstance(entry["bug_details"], list):
                for bug in entry["bug_details"]:
                    # create the complete bug information for the signature
                    bug_info = {
                        "method_name": bug.get("method", "unknown"),
                        "bug_type": bug.get("type", "unknown"),
                        "error": bug.get("description", ""),
                        "found_in_iteration": iteration
                    }
                    
                    # use the robust signature function to create a unique signature
                    bug_signature = self._create_robust_bug_signature(bug_info)
                    
                    # add to the current iteration's bug set
                    current_iteration_bugs.add(bug_signature)
                    
                    # if it is a verified real bug, add to the verified bug set
                    if bug.get("verified", False) and bug.get("is_real_bug", False):
                        current_iteration_verified_bugs.add(bug_signature)
            else:
                # process the detected_bugs field
                detected_bugs = entry.get("detected_bugs", None)
                if detected_bugs is not None:
                    if isinstance(detected_bugs, list):
                        # create a robust signature for each bug
                        for bug in detected_bugs:
                            bug_info = {
                                "method_name": bug if isinstance(bug, str) else f"unknown_bug_{iteration}",
                                "bug_type": "unknown",
                                "found_in_iteration": iteration
                            }
                            current_iteration_bugs.add(self._create_robust_bug_signature(bug_info))
                    elif isinstance(detected_bugs, (int, float)) and detected_bugs > 0:
                        # create a placeholder bug for each count and generate the signature
                        for j in range(int(detected_bugs)):
                            bug_info = {
                                "method_name": f"unknown_bug_{iteration}_{j}",
                                "bug_type": "unknown",
                                "found_in_iteration": iteration
                            }
                            current_iteration_bugs.add(self._create_robust_bug_signature(bug_info))
                
                # process the verified_bugs field
                verified_bugs = entry.get("verified_bugs", None)
                if verified_bugs is not None:
                    if isinstance(verified_bugs, list):
                        # create a robust signature for each verified bug
                        for bug in verified_bugs:
                            bug_info = {
                                "method_name": bug if isinstance(bug, str) else f"unknown_verified_bug_{iteration}",
                                "bug_type": "unknown",
                                "found_in_iteration": iteration
                            }
                            current_iteration_verified_bugs.add(self._create_robust_bug_signature(bug_info))
                    elif isinstance(verified_bugs, (int, float)) and verified_bugs > 0:
                        # create a placeholder verified bug for each count and generate the signature
                        for j in range(int(verified_bugs)):
                            bug_info = {
                                "method_name": f"unknown_verified_bug_{iteration}_{j}",
                                "bug_type": "unknown",
                                "found_in_iteration": iteration
                            }
                            current_iteration_verified_bugs.add(self._create_robust_bug_signature(bug_info))
                
                # also check the bugs_found field as a fallback
                bugs_found = entry.get("bugs_found", 0)
                if isinstance(bugs_found, (int, float)) and bugs_found > 0 and not current_iteration_bugs:
                    # if other fields do not provide information, use bugs_found
                    for j in range(int(bugs_found)):
                        bug_info = {
                            "method_name": f"bugs_found_{iteration}_{j}",
                            "bug_type": "unknown",
                            "found_in_iteration": iteration
                        }
                        current_iteration_bugs.add(self._create_robust_bug_signature(bug_info))
            
            # update the unique bug set
            unique_bug_signatures.update(current_iteration_bugs)
            unique_verified_bug_signatures.update(current_iteration_verified_bugs)
            
            # the number of bugs detected in the current iteration
            current_detected_count = len(current_iteration_bugs)
            current_verified_count = len(current_iteration_verified_bugs)
            
            # the cumulative number of unique bugs
            cumulative_detected = len(unique_bug_signatures)
            cumulative_verified = len(unique_verified_bug_signatures)
            
            # build the trend entry
            trend_entry = {
                "iteration": iteration,
                "coverage": entry.get("coverage", 0.0),
                "best_coverage": entry.get("current_best_coverage", entry.get("coverage", 0.0)),
                "reward": entry.get("reward", 0.0),
                "detected_bugs": current_detected_count,       # the number of bugs detected in the current iteration
                "verified_bugs": current_verified_count,       # the number of verified bugs in the current iteration
                "cumulative_detected_bugs": cumulative_detected,  # the cumulative number of unique bugs
                "cumulative_verified_bugs": cumulative_verified   # the cumulative number of unique verified bugs
            }
            coverage_trend.append(trend_entry)
            
        # log the trend statistics
        logger.info(f"generated {len(coverage_trend)} data points for the coverage trend, " +
                f"cumulative detected {cumulative_detected} unique bugs, " +
                f"cumulative verified {cumulative_verified} unique bugs")
        
        return coverage_trend

    def _calculate_coverage_improvement_rate(self):
        """
        calculate the coverage improvement rate from the history
        
        Returns:
        float: coverage improvement rate
        """
        # if there is no history, return 0
        if not hasattr(self, "history") or not self.history:
            return 0.0
        
        # get the initial coverage and final coverage
        initial_coverage = self.history[0].get("coverage", 0.0)
        final_coverage = self.current_coverage
        
        # calculate the coverage improvement rate
        improvement = final_coverage - initial_coverage
        iterations = len(self.history)
        
        if iterations <= 1:
            return improvement
        
        # calculate the average improvement rate for each iteration
        improvement_rate = improvement / (iterations - 1)
        return round(improvement_rate, 4)
    
    


    def update_best_tests(self, state, reward, iteration):
        """
        update the best test code based on the reward
        
        Parameters:
        state (FATestState): current state
        reward (float): reward value
        iteration (int): current iteration
        """
        # if the state is None, skip
        if not state:
            return
            
        # check the coverage of this state
        current_state_coverage = 0.0
        if hasattr(state, "coverage") and state.coverage > 0:
            current_state_coverage = state.coverage
            
        # first check if there is a higher coverage
        if current_state_coverage > self.current_coverage:
            logger.info(f"Found higher coverage at iteration {iteration}: {current_state_coverage:.2f}% > {self.current_coverage:.2f}%")
            self.current_coverage = current_state_coverage
            
            # ensure the state and test code with the highest coverage are saved
            self.best_state = state
            self.best_test = state.test_code
            self.best_reward = max(reward, self.best_reward)  # use the maximum of the current reward and the best reward
            logger.info(f"Updated best test due to higher coverage: {self.current_coverage:.2f}%")
        
        # if the coverage is the same but the reward is higher, also update
        elif current_state_coverage == self.current_coverage and reward > self.best_reward:
            logger.info(f"Found better test at iteration {iteration} with same coverage: reward={reward:.4f} > {self.best_reward:.4f}")
            self.best_state = state
            self.best_test = state.test_code
            self.best_reward = reward
        
        # then check the case where the reward is higher (only consider when the coverage is not less than 80% of the highest coverage)
        elif reward > self.best_reward and current_state_coverage >= self.current_coverage * 0.8:
            logger.info(f"Found better test at iteration {iteration}: reward={reward:.4f} > {self.best_reward:.4f}")
            
            # update the best state and test
            self.best_state = state
            self.best_test = state.test_code
            self.best_reward = reward
        
        # record the high coverage iteration
        if (self.current_coverage >= 80.0 and 
            self.metrics["iterations_to_high_coverage"] is None):
            self.metrics["iterations_to_high_coverage"] = iteration
            
            # update the logic coverage metrics
            if hasattr(state, "covered_failures"):
                self.best_pattern_coverage = len(state.covered_failures)
                self.metrics["high_risk_patterns_covered"] = len([
                    pattern_id for pattern_id in state.covered_failures
                    if any(p.get("risk_level") == "high" for p in self.failures
                        if f"{p['type']}_{p['location']}" == pattern_id)
                ])
                
            if hasattr(state, "covered_branch_conditions"):
                self.best_branch_coverage = len(state.covered_branch_conditions)
                
                # calculate the covered boundary conditions
                boundary_conditions = set(
                    cond_id for cond_id in state.covered_branch_conditions
                    if any(c.get("type") in ["if_condition", "while_loop", "for_loop"] 
                        for c in self.f_model.boundary_conditions
                        if f"{c['method']}_{c['line']}" == cond_id)
                )
                self.metrics["boundary_conditions_covered"] = len(boundary_conditions)
                
                # calculate the covered logical operations
                operations = set(
                    cond_id for cond_id in state.covered_branch_conditions
                    if any(c.get("operation") in ["&&", "||", "!=", "=="] 
                        for c in self.f_model.operations
                        if f"{c['method']}_{c['line']}" == cond_id)
                )
                self.metrics["operations_covered"] = len(operations)
        
        # save the copy of the test code with the highest coverage
        if current_state_coverage >= self.current_coverage * 0.9:
            # only save the test code with enough high coverage
            coverage_str = f"{current_state_coverage:.2f}".replace(".", "_")
            self.high_coverage_tests[coverage_str] = state.test_code

    def record_history(self, node, iteration, reward):
        """
        record the execution history for analysis
        
        Parameters:
        node (FA_MCTSNode): current node
        iteration (int): current iteration
        reward (float): reward value
        """
        # check the validity of the node
        if not node or not node.state:
            logger.warning(f"Attempted to record history for invalid node at iteration {iteration}")
            return
        
        # get the latest coverage - use the current best coverage first
        coverage = 0.0
        if hasattr(self, "current_coverage") and self.current_coverage > 0:
            coverage = self.current_coverage
        elif hasattr(self, "best_state") and self.best_state and hasattr(self.best_state, "coverage"):
            coverage = self.best_state.coverage
        elif hasattr(node.state, "coverage"):
            coverage = node.state.coverage
        
        # get the bug information - use the verified_bug_methods first
        bugs_found = 0
        bug_details = []
        detected_bugs = []
        verified_bugs = []
        
        # get the bugs from the verified bug list
        if hasattr(self, "verified_bug_methods") and self.verified_bug_methods:
            # print("--------------------------------")
            # print("verified_bug_methods")
            # print(self.verified_bug_methods)
            # print("--------------------------------")
            # print("--------------------------------")
            for bug_method in self.verified_bug_methods:
                method_name = bug_method.get("method_name", "unknown")
                bug_info = {
                    "method": method_name,
                    "type": bug_method.get("bug_type", "unknown"),
                    "verified": bug_method.get("verified", False),
                    "is_real_bug": bug_method.get("is_real_bug", False)
                }
                
                # print("bug_info")
                # print(bug_info)
                
                bug_details.append(bug_info)
                detected_bugs.append(method_name)
                
                # if the bug is verified as a real bug
                if bug_info["verified"] and bug_info["is_real_bug"]:
                    verified_bugs.append(method_name)
            # print("--------------------------------")     
            bugs_found = len(detected_bugs)
        
        # if there is no verified bug, but the current node state has bug information
        if bugs_found == 0 and hasattr(node.state, "logical_bugs") and node.state.logical_bugs:
            # collect the bug details
            for bug in node.state.logical_bugs:
                method_name = bug.get("test_method", bug.get("method_name", "unknown"))
                
                bug_info = {
                    "method": method_name,
                    "type": bug.get("bug_type", "unknown"),
                    "verified": bug.get("verified", False),
                    "is_real_bug": bug.get("is_real_bug", False)
                }
                bug_details.append(bug_info)
                detected_bugs.append(method_name)
                
                # if the bug is verified as a real bug
                if bug_info["verified"] and bug_info["is_real_bug"]:
                    verified_bugs.append(method_name)
                    
            bugs_found = len(detected_bugs)
        
        # get the logic pattern and branch condition coverage data
        logic_scenario_coverage = 0
        branch_condition_coverage = 0
        
        if hasattr(node.state, "covered_failures"):
            logic_scenario_coverage = len(node.state.covered_failures)
        
        if hasattr(node.state, "covered_branch_conditions"):
            branch_condition_coverage = len(node.state.covered_branch_conditions)
        
        # get the total number of logic patterns and branch conditions, for more information
        total_failures = len(self.failures) if hasattr(self, "failures") and self.failures else 0
        total_branch_conditions = len(self.f_model.boundary_conditions) if hasattr(self.f_model, "boundary_conditions") else 10
        
        # record the node statistics, including more information
        logger.info(f"Recording history for node (iteration {iteration}): visits={node.visits}, " + 
                f"failures={logic_scenario_coverage}/{total_failures}, " +
                f"branch_conditions={branch_condition_coverage}/{total_branch_conditions}, " +
                f"coverage={coverage:.2f}%, bugs={bugs_found}")
        
        # create the history entry
        entry = {
            "iteration": iteration,
            "reward": round(float(reward), 5),
            "coverage": coverage,  # use the correct coverage
            "bugs_found": bugs_found,  # set the correct bugs_found number
            "action": node.action if node.action else "root",
            "logic_scenario_coverage": logic_scenario_coverage,
            "branch_condition_coverage": branch_condition_coverage,
            "visits": node.visits,
            "wins": round(float(hasattr(node, 'wins') and node.wins or (hasattr(node, 'value') and node.value or 0.0)), 5),
            "logic_bug_rewards": round(float(hasattr(node, 'logic_bug_rewards') and node.logic_bug_rewards or 0.0), 5),
            "failure_coverage_rewards": round(float(hasattr(node, 'failure_coverage_rewards') and node.failure_coverage_rewards or 0.0), 5),
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "detected_bugs": detected_bugs,  # add the detected bugs
            "verified_bugs": verified_bugs,  # add the verified bugs
            "bug_details": bug_details,  # add the bug details
            "test_code": node.state.test_code if hasattr(node.state, "test_code") else None  # save the test code of the current node
        }
        
        # add to the history
        self.history.append(entry)
        
        # record the UCB score distribution for analysis
        if node.parent and node.parent.children:
            scores = []
            for child in node.parent.children:
                if child.visits > 0:
                    # select the correct attribute based on the node type
                    win_value = hasattr(child, 'wins') and child.wins or (hasattr(child, 'value') and child.value or 0.0)
                    exploitation = win_value / child.visits
                    exploration = self.exploration_weight * (2 * (node.parent.visits / child.visits) ** 0.5)
                    
                    # the bug reward for the FailureAwareMCTSNode
                    logic_bonus = 0.0
                    if hasattr(child, 'logic_bug_rewards') and hasattr(child, 'failure_coverage_rewards') and hasattr(child, 'high_risk_pattern_rewards'):
                        logic_bonus = self.f_weight * (
                            (child.logic_bug_rewards / child.visits) + 
                            (child.failure_coverage_rewards / child.visits) +
                            (child.high_risk_pattern_rewards / child.visits) +
                            (0.2 if hasattr(child, 'is_novel') and child.is_novel else 0.0)
                        )
                    
                    scores.append(exploitation + exploration + logic_bonus)
            
            if scores and hasattr(self, "metrics"):
                self.metrics["ucb_score_distribution"].append({
                    "iteration": iteration,
                    "min": min(scores),
                    "max": max(scores),
                    "avg": sum(scores) / len(scores),
                    "count": len(scores)
                })



    def check_termination(self, iteration):
        """
        Check if search should terminate early
        
        Parameters:
        iteration (int): Current iteration
        
        Returns:
        bool: True if search should terminate
        """
        # Check if maximum iterations reached
        if iteration >= self.max_iterations:
            return True
            
        # Check if we've reached target coverage with bugs
        target_coverage = 101.0  # Very high coverage threshold
        if self.current_coverage >= target_coverage and self.bugs_found > 0:
            logger.info(f"Reached high coverage ({self.current_coverage}%) with bugs found, terminating early")
            return True
            
        # Check if we've found enough bugs
        # Higher threshold to allow more iterations
        if self.bugs_found >= self.bugs_threshold:
            logger.info(f"Found {self.bugs_found} bugs (threshold: {self.bugs_threshold}), terminating early")
            return True
            
        # Check if no progress in last 5 iterations
        if iteration > 5 and len(self.history) >= 15:
            last_rewards = [entry["reward"] for entry in self.history[-5:]]
            if all(abs(last_rewards[0] - r) < 0.001 for r in last_rewards[1:]):
                logger.info("No progress in last 5 iterations, terminating early")
                return True
            
        # Continue search
        return False
    
    def save_metrics(self):
        """Save metrics for academic evaluation"""
        # Calculate final metrics
        self.metrics["total_test_methods"] = sum(1 for m in self.best_state.test_methods) if self.best_state else 0
        
        # Calculate bug detection rate
        if self.metrics["total_test_methods"] > 0:
            self.metrics["bug_detection_rate"] = (
                self.metrics["total_bug_tests"] / self.metrics["total_test_methods"]
            )
        else:
            self.metrics["bug_detection_rate"] = 0.0
        
        # Convert any sets to lists for JSON serialization
        metrics_copy = {}
        for key, value in self.metrics.items():
            if isinstance(value, set):
                metrics_copy[key] = list(value)
            elif isinstance(value, dict):
                # Handle nested dictionaries
                metrics_copy[key] = {}
                for subkey, subvalue in value.items():
                    if isinstance(subvalue, set):
                        metrics_copy[key][subkey] = list(subvalue)
                    else:
                        metrics_copy[key][subkey] = subvalue
            else:
                metrics_copy[key] = value
                
        # Save metrics to file
        metrics_file = os.path.join(self.project_dir, f"{self.class_name}_logic_metrics.json")
        try:
            import json
            with open(metrics_file, 'w', encoding='utf-8') as f:
                json.dump(metrics_copy, f, indent=2)
            logger.info(f"Saved metrics to {metrics_file}")
        except Exception as e:
            logger.error(f"Failed to save metrics: {str(e)}")
    
    def selection(self, node):
        """
        select a promising node for expansion
        
        Returns:
        FA_MCTSNode: the selected node
        """
        # start from the provided node
        current = node
        
        # record the path for logging
        path = []
        path.append("root")
        
        # add: strategy history tracking
        action_type_history = []
        
        # add diversity factor
        current_iteration = getattr(self, 'current_iteration', 0)
        force_exploration = (current_iteration % 3 == 0)  # force exploration every 3 iterations
        
        # select the child node with the highest UCB score, until reaching a leaf node or a partially expanded node
        while current.is_fully_expanded() and current.children:
            # if force exploration, use a different selection strategy
            if force_exploration and len(current.children) > 1:
                # calculate the UCB score for all child nodes
                child_scores = []
                for child in current.children:
                    if child.visits > 0:
                        exploitation = child.wins / child.visits
                        exploration = self.exploration_weight * (2 * (current.visits / child.visits) ** 0.5)
                        
                        # the bug reward for the FailureAwareMCTSNode
                        logic_bonus = 0.0
                        if hasattr(child, 'logic_bug_rewards') and hasattr(child, 'failure_coverage_rewards'):
                            logic_bug_term = child.logic_bug_rewards / child.visits
                            logic_coverage_term = child.failure_coverage_rewards / child.visits
                            logic_bonus = self.f_weight * (logic_bug_term + logic_coverage_term)
                        
                        # add random perturbation to increase diversity
                        random_factor = random.random() * 0.3
                        
                        # get the action type
                        action_type = "unknown"
                        if child.action and isinstance(child.action, dict) and 'type' in child.action:
                            action_type = child.action['type']
                        
                        # add reward for unused action types
                        diversity_bonus = 0.0
                        if action_type not in action_type_history[-2:] if action_type_history else True:
                            diversity_bonus = 0.2
                        
                        score = exploitation + exploration + logic_bonus + random_factor + diversity_bonus
                        child_scores.append((child, score))
                    else:
                        child_scores.append((child, float('inf')))  # the unvisited node has the highest score
                
                # sort by score, but randomly select one of the top 3, not always the best
                sorted_children = sorted(child_scores, key=lambda x: x[1], reverse=True)
                if len(sorted_children) >= 3:
                    # randomly select one of the top 3
                    idx = random.randint(0, min(2, len(sorted_children)-1))
                    current = sorted_children[idx][0]
                else:
                    # if less than 3, randomly select one
                    current = random.choice([c[0] for c in sorted_children])
            else:
                # regular selection - check the recent strategy selection history
                if len(action_type_history) >= 2:
                    # if the same strategy type is selected consecutively twice, try to encourage diversity
                    recent_actions = action_type_history[-2:]
                    if len(set(recent_actions)) == 1:  # the recent actions are all the same
                        # temporarily increase the exploration weight, encourage diversity
                        temp_exploration_weight = self.exploration_weight * 1.5
                        current = current.best_child(
                            exploration_weight=temp_exploration_weight,
                            f_weight=self.f_weight
                        )
                    else:
                        # regular selection
                        current = current.best_child(
                            exploration_weight=self.exploration_weight,
                            f_weight=self.f_weight
                        )
                else:
                    # regular selection
                    current = current.best_child(
                        exploration_weight=self.exploration_weight,
                        f_weight=self.f_weight
                    )
            
            # add: record the action type of the current node
            if current.action and isinstance(current.action, dict) and 'type' in current.action:
                action_type = current.action['type']
                action_type_history.append(action_type)
                # save the last action type, for the diversity reward in the UCB calculation
                current.parent.last_action_type = action_type
            
            # add to the path - record the action type for logging
            if current.action and isinstance(current.action, dict) and 'type' in current.action:
                action_type = current.action['type']
                path.append(action_type)
            elif current.action:
                path.append(str(current.action))
            else:
                path.append("unknown")
        
        # log the execution path
        path_str = " -> ".join(path)
        logger.info(f"Node execution path: {path_str}")
            
        return current

    def expansion(self, node):
        """
        Expand a node by selecting an unexplored action
        
        Parameters:
        node (FA_MCTSNode): Node to expand
        
        Returns:
        FA_MCTSNode: New expanded node
        """
        # Get possible actions
        possible_actions = node.generate_possible_actions(
            test_prompt=self.test_prompt,
            source_code=self.source_code,
            uncovered_data={"uncovered_lines": node.state.uncovered_lines} if hasattr(node.state, "uncovered_lines") else None,
            f_model=self.f_model,  # add the failure aware model parameter
            failures=self.failures,  # add the failure aware pattern parameter
            strategy_selector=self.strategy_selector  # add the strategy selector parameter
        )
        
        # If no actions, mark as fully expanded and return
        if not possible_actions:
            node.expanded = True
            logger.info("No possible actions, node marked as fully expanded")
            return node
            
        # Select action (random selection for expansion)
        # print("--------------------------------")
        # print(f"possible_actions: {possible_actions}")
        # print("--------------------------------")
        action = random.choice(possible_actions)

        # print("--------------------------------")
        # print(f"action: {action}")
        # print("--------------------------------")
        
        # Log the selected action in detail
        if isinstance(action, dict):
            action_type = action.get("type", "unknown")
            action_desc = action.get("description", "No description")
            
            # Log more details based on action type
            if action_type == "boundary_test" and "condition" in action and "line" in action:
                logger.info(f"Selected action: {action_type} - Target condition at line {action['line']}: {action['condition']}")
            elif action_type == "expression_test" and "operation" in action and "line" in action:
                logger.info(f"Selected action: {action_type} - Target operation at line {action['line']}: {action['operation']}")
            elif action_type == "target_line" and "line" in action and "content" in action:
                logger.info(f"Selected action: {action_type} - Target line {action['line']}: {action['content']}")
            elif action_type == "bug_pattern_test" and "pattern_type" in action:
                logger.info(f"Selected action: {action_type} - Target pattern: {action['pattern_type']}")
            else:
                logger.info(f"Selected action: {action_type} - {action_desc}")
        else:
            logger.info(f"Selected action: {action}")
        
        # Create new test state
        node.used_action.append(action)
        new_state = self._apply_action(node.state, action)
        
        # Check if state creation failed
        if not new_state:
            logger.warning(f"Failed to create new state for action: {action}")
            # Mark as expanded if all actions have been tried
            if len(node.children) >= len(possible_actions):
                node.expanded = True
            return node
            
        # Create child node
        child_node = node.add_child(new_state, action)
        
        # Mark node as fully expanded if all actions have been tried
        if len(node.children) >= len(possible_actions):
            node.expanded = True
            
        # Update strategy effectiveness metrics
        if "strategy" in action:
            strategy = action["strategy"]
            self.metrics["strategy_effectiveness"][strategy]["used"] += 1
            
        return child_node
        
    def _apply_action(self, state, action):
        """
        apply an action to create a new test state
        
        Parameters:
        state (FATestState): the current state
        action (dict): the action to apply
        
        Returns:
        FATestState: the new state or None if failed
        """
        if not state:
            return None
            
        try:
            # Log start of action application
            action_type = action.get("type", "unknown") if isinstance(action, dict) else str(action)
            logger.info(f"Applying action: {action_type}")
            
            # More detailed logging based on action type
            if isinstance(action, dict):
                if "strategy" in action:
                    logger.info(f"Using strategy: {action['strategy']}")
                if "description" in action:
                    logger.info(f"Action description: {action['description']}")
            
            # generate the prompt for the action
            if action_type == "business_logic_test":
                # Create a special prompt for business logic issues
                prompt = self._create_business_logic_test_prompt(state, action)
            else:
                # Regular actions use the normal prompt creation
                prompt = self.create_logic_aware_action_prompt(state, action)
            
            # use LLM to generate new test code
            from feedback import call_anthropic_api, call_gpt_api, call_deepseek_api, extract_java_code
            
            # record the LLM call
            logger.info(f"Calling LLM API to generate test code for action: {action_type}")
            
            # call the LLM API
            llm_response = call_anthropic_api(prompt)
            # llm_response = call_gpt_api(prompt)
            # llm_response = call_deepseek_api(prompt)
            # extract the test code
            new_test_code = extract_java_code(llm_response)
            
            # check if the code extraction failed
            if not new_test_code:
                logger.warning(f"Failed to extract test code from LLM response: {action}")
                return None
                
            # log the code size, not the entire code
            code_size = len(new_test_code)
            logger.info(f"Generated test code size: {code_size} characters")
                
            # save the current coverage, to restore in the new state
            previous_coverage = getattr(state, "coverage", 0.0)
            previous_patterns = getattr(state, "covered_failures", set()) if hasattr(state, "covered_failures") else set()
            previous_conditions = getattr(state, "covered_branch_conditions", set()) if hasattr(state, "covered_branch_conditions") else set()
                
            # create a new test state
            new_state = FATestState(
                test_code=new_test_code,
                class_name=self.class_name,
                package_name=self.package_name,
                project_dir=self.project_dir,
                source_code=self.source_code,
                f_model=self.f_model,
                failures=self.failures,
                project_type=getattr(self, 'project_type', 'maven')
            )
            
            # add the metadata for the action that generated this state
            new_state.metadata = {
                "action": action,
                "parent_coverage": previous_coverage,
                "generation_method": "logic_aware_mcts"
            }

            # NEW: Pass business logic analysis to new state
            if hasattr(state, 'business_logic_analysis'):
                new_state.business_logic_analysis = state.business_logic_analysis
            
            # handle the compilation errors in the parent state, so the new state knows these errors
            if hasattr(state, "compilation_errors") and state.compilation_errors:
                new_state.previous_compilation_errors = state.compilation_errors
            
            # initialize the coverage and pattern coverage before evaluating it
            if previous_coverage > 0:
                new_state.coverage = previous_coverage
            
            if previous_patterns:
                new_state.covered_failures = previous_patterns.copy()
                
            if previous_conditions:
                new_state.covered_branch_conditions = previous_conditions.copy()
            
            # evaluate the new state
            logger.info(f"Evaluating new state for action: {action_type}")
            new_state.evaluate(verify_bugs=self.verify_bugs_mode == "immediate")
            
            # Check if we've successfully fixed compilation errors
            if action_type == "fix_compilation_errors":
                if hasattr(new_state, "compilation_errors") and new_state.compilation_errors:
                    logger.warning(f"Compilation errors still exist after fix attempt: {new_state.compilation_errors[:2]}")
                    # Mark this path as failed
                    if "path_signature" in action:
                        self.failed_fix_paths.add(action["path_signature"])
                        logger.info(f"Marked path as failed: {action['path_signature']}")
                else:
                    logger.info("Successfully fixed compilation errors!")
            
            # ensure the coverage is not lost after evaluation
            if not hasattr(new_state, "coverage") or new_state.coverage <= 0:
                new_state.coverage = previous_coverage
                logger.debug(f"Restored previous coverage {previous_coverage} after evaluation")
            
            # record the evaluation result of the new state
            new_coverage = getattr(new_state, "coverage", 0.0)
            has_bugs = hasattr(new_state, "has_bugs") and new_state.has_bugs
            bug_count = getattr(new_state, "count_logical_bugs", lambda: 0)() if has_bugs else 0
            
            logger.info(f"Action {action_type} result: coverage={new_coverage:.2f}%, " +
                      f"found logical bugs: {has_bugs} (count: {bug_count})")
            
            return new_state
                
        except Exception as e:
            logger.error(f"Error applying action: {str(e)}")
            logger.error(traceback.format_exc())
            return None
    

    def _create_business_logic_test_prompt(self, state, action):
        """
        Create a specialized prompt for testing business logic issues
        
        Parameters:
        state (FATestState): Current state
        action (dict): Business logic action
        
        Returns:
        str: Generated prompt
        """
        issue_type = action.get("issue_type", "unknown")
        issue_method = action.get("method", "")
        issue_description = action.get("description", "")
        
        # Extract more details about the issue from business logic analysis
        issue_details = {}
        if hasattr(state, 'business_logic_analysis'):
            for issue in state.business_logic_analysis.get('potential_bugs', []):
                if issue.get('method') == issue_method and issue.get('type') == issue_type:
                    issue_details = issue
                    break
        
        semantic_signals = issue_details.get('semantic_signals', {})
        implementation_features = issue_details.get('implementation_features', {})
        
        # Build specialized prompt
        prompt = f"""
CRITICAL REQUIREMENTS:
1. DO NOT use @Nested annotations or nested test classes - they cause coverage tracking issues
2. Generate a COMPLETE test class with ALL methods intact - do not omit any code
3. DO NOT use placeholders like "... existing code ..." or similar comments
4. Your response MUST contain the ENTIRE test class that can compile without modifications

STRICT ANTI-MOCKING REQUIREMENTS:
- ABSOLUTELY NO use of any mocking frameworks (Mockito, EasyMock, PowerMock, etc.)
- ABSOLUTELY NO @Mock, @MockBean, @InjectMocks, or any mock-related annotations
- ABSOLUTELY NO imports from org.mockito.* or static imports from Mockito
- ABSOLUTELY NO mock(), when(), verify(), or any mocking methods
- Use ONLY real objects and direct instantiation for testing
- Create real instances of dependencies instead of mocks

You are an expert Java test engineer focusing on detecting BUSINESS LOGIC BUGS.
You need to extend the following test class for {self.class_name} to find a specific business logic bug.

BUSINESS LOGIC ISSUE DETAILS:
- Method with potential issue: {issue_method}
- Issue type: {issue_type}
- Description: {issue_description}
- Expected behavior: {semantic_signals.get('expected_behavior', 'Not specified')}
- Actual behavior: {semantic_signals.get('actual_behavior', 'Not specified')}
- Specifically test: {issue_details.get('test_strategy', 'all edge cases and logical conditions')}

Current test coverage: {state.coverage:.2f}%

Here is the existing test code:
```java
{state.test_code}
```
Here is the source code being tested:
```java
{self.source_code}
```
"""
        # inject: dependency API context, to prevent the creation of dependency methods/fields
        dep_ctx = self._extract_dependency_context_from_prompt()
        if dep_ctx:
            prompt += f"\nADDITIONAL CONTEXT (dependencies and rules):\n{dep_ctx}\n"

        return prompt



    def create_logic_aware_action_prompt(self, state, action):
        """
        Create a prompt for the given action with logic awareness
        
        Parameters:
        state (FATestState): Current state
        action (dict): Action to apply
        
        Returns:
        str: Generated prompt
        """
        action_type = action.get("type", "fallback")
        
        # Base prompt with strong warnings about nested classes and complete code
        prompt = f"""
CRITICAL REQUIREMENTS - READ CAREFULLY:
1. DO NOT use @Nested annotations or nested test classes - they cause coverage tracking issues
2. Generate a COMPLETE test class with ALL methods intact - do not omit any code
3. ABSOLUTELY FORBIDDEN: placeholders like "... existing code ...", "// [Previous imports remain exactly the same]", "// ... existing code ...", "// All previous fields and methods remain exactly the same", or ANY similar comments that indicate omitted code
4. Your response MUST contain the ENTIRE test class that can compile without modifications
5. WRITE OUT EVERY SINGLE LINE OF CODE - no shortcuts, abbreviations, or omissions allowed
6. If the existing test class has 100 lines, your response should contain at least 100 lines plus your additions
7. Copy every import statement, every field declaration, every existing method in full
8. NEVER use comments to indicate that code continues - write the actual code

STRICT ANTI-MOCKING REQUIREMENTS:
- ABSOLUTELY NO use of any mocking frameworks (Mockito, EasyMock, PowerMock, etc.)
- ABSOLUTELY NO @Mock, @MockBean, @InjectMocks, or any mock-related annotations
- ABSOLUTELY NO imports from org.mockito.* or static imports from Mockito
- ABSOLUTELY NO mock(), when(), verify(), or any mocking methods
- Use ONLY real objects and direct instantiation for testing
- Create real instances of dependencies instead of mocks

You are an expert Java test engineer focusing on detecting logical bugs.
You need to extend the following test class for {self.class_name} to find bugs.

Focus specifically on finding logical bugs related to:
1. Boundary conditions
2. Boolean logic errors
3. Operator precedence issues
4. Off-by-one errors
5. Null handling problems
6. Resource management issues
7. Exception handling defects
8. Data operation bugs

Current test coverage: {state.coverage:.2f}%

Here is the existing test code:
```java
{state.test_code}
```
"""

        # Add source code context
        prompt += f"""
Here is the source code being tested:
```java
{self.source_code}
```
"""
        # 注入：依赖API上下文，防止臆造依赖方法/字段
        dep_ctx = self._extract_dependency_context_from_prompt()
        if dep_ctx:
            prompt += f"\nADDITIONAL CONTEXT (dependencies and rules):\n{dep_ctx}\n"

        # Handle fix_compilation_errors action specifically
        if action_type == "fix_compilation_errors":
            prompt += f"""

IMPORTANT: The current test code has COMPILATION ERRORS that MUST be fixed!

Compilation errors found:
"""
            # Include specific compilation errors with analysis
            if "errors" in action and action["errors"]:
                analyzed_errors = self._analyze_compilation_errors(action["errors"])
                for i, (error, suggestion) in enumerate(analyzed_errors[:10], 1):
                    prompt += f"{i}. ERROR: {error}\n"
                    if suggestion:
                        prompt += f"   SUGGESTED FIX: {suggestion}\n"
                if len(action["errors"]) > 10:
                    prompt += f"... and {len(action['errors']) - 10} more errors\n"
            
            prompt += """

Your task is to:
1. Fix ALL compilation errors in the test code above
2. Make sure the fixed code is syntactically correct and can compile
3. Preserve all existing test logic while fixing the errors
4. Add any missing imports if needed
5. Fix any incorrect method calls or type mismatches
6. Ensure proper Java syntax throughout
7. IMPORTANT: Write out the COMPLETE test class with all fixes applied

Common compilation errors to fix:
- Missing semicolons
- Unclosed brackets or parentheses
- Invalid comment syntax (e.g., incomplete comment blocks)
- Missing imports
- Type mismatches
- Undefined methods or variables

Remember: You MUST provide the COMPLETE test class, not just the fixes!
"""
        else:
            # Add specific instructions based on action type
            if action_type == "boundary_test":
                prompt += f"""

Add new test methods to specifically test the boundary condition:
Condition: {action.get('condition', 'N/A')}
Line: {action.get('line', 'N/A')}

Focus on edge cases around this boundary (e.g., value-1, value, value+1).
"""
            elif action_type == "expression_test":
                prompt += f"""

Add new test methods to test the logical expression:
Expression: {action.get('operation', 'N/A')}
Line: {action.get('line', 'N/A')}

Test all combinations of boolean values and edge cases.
"""
            elif action_type == "exception_test":
                prompt += f"""

Add new test methods to test exception handling paths.
Focus on triggering exceptions and verifying proper handling.
"""
            elif action_type == "target_line":
                prompt += f"""

Add new test methods to cover the uncovered line:
Line {action.get('line', 'N/A')}: {action.get('content', 'N/A')}

Create test cases that will execute this specific line.
"""
            elif action_type == "business_logic_test":
                prompt += f"""

Add new test methods to test the business logic issue:
Issue Type: {action.get('issue_type', 'N/A')}
Method: {action.get('method', 'N/A')}
Description: {action.get('description', 'N/A')}

Focus on testing the specific business logic concern identified.
"""

        # Add final reminder
        prompt += """

FINAL REMINDER: 
- Your response MUST be a COMPLETE, COMPILABLE Java test class
- Include ALL imports, ALL fields, ALL existing methods, and your new additions
- DO NOT use any comments like "// ... existing code ..." or "// [Previous test methods remain exactly as shown in the original code]"
- The test class should be ready to compile and run immediately
- Every single line of the original test code must be included in your response
"""

        return prompt

    def simulation(self, node):
        """
        Simulate the node to estimate the value, but only detect bugs without verification
        
        Parameters:
        node (FA_MCTSNode): the node to simulate
        
        Returns:
        float: reward value
        """
        # if the node has a state, use it to calculate the reward
        if node.state:
            # collect potential bugs, but do not verify them immediately
            if hasattr(node.state, "detected_bugs") and node.state.detected_bugs:
                for bug in node.state.detected_bugs:
                    # create bug information
                    bug_info = {
                        "test_method": bug.get("test_method", "unknown"),
                        "bug_type": bug.get("type", "unknown"),
                        "error": bug.get("error", ""),
                        "severity": bug.get("severity", "medium"),
                        "method_code": self._extract_method_from_test_code(node.state.test_code, bug.get("test_method", "")),
                        "found_in_iteration": getattr(self, "current_iteration", 0),
                        "original_test_code": node.state.test_code  # save the complete original test code
                    }
                    
                    # create bug signature for deduplication
                    bug_signature = self._create_bug_signature(bug_info)
                    
                    # if this is a new bug signature, add to the candidate list
                    if bug_signature not in self.potential_bug_signatures:
                        bug_info["bug_signature"] = bug_signature
                        self.potential_bug_signatures.add(bug_signature)
                        self.potential_bugs.append(bug_info)
                        logger.info(f"Detected potential bug: {bug_info['test_method']} (type: {bug_info['bug_type']})")
                
                # still consider the number of unverified bugs when calculating the reward
                reward = self.calculate_failure_aware_reward(node.state)
                return reward
            
            # if there is no bug but has a state, calculate the reward normally
            reward = self.calculate_failure_aware_reward(node.state)
            return reward
        
        # if there is no state, return zero reward
        return 0.0




    def backpropagation(self, node, reward):
        """
        Backpropagate reward through the tree
        
        Parameters:
        node (FA_MCTSNode): Node to start backpropagation from
        reward (float): Reward value
        """
        # Get bug type if available
        bug_type = None
        pattern_coverage = None
        branch_coverage = None
        
        if node.state:
            # Extract coverage data
            if hasattr(node.state, "covered_failures"):
                pattern_coverage = node.state.covered_failures
            
            if hasattr(node.state, "covered_branch_conditions"):
                branch_coverage = node.state.covered_branch_conditions
            
            # Extract bug type if available
            if hasattr(node.state, "has_bugs") and node.state.has_bugs:
                # Use the most severe bug type for backpropagation
                for bug in node.state.logical_bugs:
                    if bug.get("severity", "medium") == "high":
                        bug_type = f"logical_{bug.get('bug_type', 'unknown')}"
                        break
            
                if not bug_type and node.state.logical_bugs:
                    bug_type = f"logical_{node.state.logical_bugs[0].get('bug_type', 'unknown')}"
        
        # Log backpropagation data for debugging
        logger.info(f"Starting backpropagation with reward={reward:.4f}, patterns={len(pattern_coverage) if pattern_coverage else 0}, " +
                   f"branches={len(branch_coverage) if branch_coverage else 0}, bug_type={bug_type}")
        
        # Backpropagate reward and coverage data
        current = node
        path = []
        
        while current:
            # Track the path for logging
            if isinstance(current.action, dict) and 'type' in current.action:
                path.append(current.action['type'])
            elif current.action:
                path.append(str(current.action))
            else:
                path.append("root")
                
            # Use more comprehensive update method that passes coverage data
            old_visits = current.visits
            old_wins = current.wins
            old_logic_rewards = getattr(current, 'logic_bug_rewards', 0.0)
            
            if hasattr(current, 'logic_bug_rewards'):
                current.update(reward, bug_type, pattern_coverage, branch_coverage)
            else:
                # Basic update for non-logic nodes
                current.update(reward)
            
            # Log the update for this node
            if hasattr(current, 'logic_bug_rewards'):
                logger.debug(f"Node updated: visits {old_visits}->{current.visits}, " +
                           f"wins {old_wins:.4f}->{current.wins:.4f}, " +
                           f"logic_rewards {old_logic_rewards:.4f}->{current.logic_bug_rewards:.4f}")
            else:
                logger.debug(f"Node updated: visits {old_visits}->{current.visits}, " +
                           f"wins {old_wins:.4f}->{current.wins:.4f}")
            
            current = current.parent
        
        # Log the backpropagation path
        path.reverse()  # Reverse to show root-to-node path
        path_str = " -> ".join(path)
        logger.info(f"Backpropagation path: {path_str}")
    

    def calculate_failure_aware_reward(self, state, parent_state=None):
        """
        Calculate failure-aware reward with improved exploration for stagnant coverage
        
        Parameters:
        state (FATestState): Test state
        parent_state (FATestState): Parent state for comparison
        
        Returns:
        float: Calculated reward
        """
        if not state:
            return 0.0
        
        # Check for compilation errors
        has_compilation_errors = hasattr(state, "compilation_errors") and state.compilation_errors
        
        # If this is a fix_compilation_errors action, reward based on fixing errors
        if hasattr(state, "metadata") and state.metadata and state.metadata.get("action", {}).get("type") == "fix_compilation_errors":
            # If had errors before but now fixed, give high reward
            had_errors_before = (hasattr(state, "previous_compilation_errors") and state.previous_compilation_errors)
            if had_errors_before and not has_compilation_errors:
                logger.info("High reward for successfully fixing compilation errors")
                return 2.0  # High reward for fixing compilation errors
            elif has_compilation_errors:
                logger.info("Low reward for failing to fix compilation errors")
                return 0.1  # Low reward for failing
        
        # For normal testing actions, low reward if compilation errors
        if has_compilation_errors:
            logger.info("Low reward due to compilation errors")
            return 0.05  # Very low reward when there are compilation errors
            
        # Base reward components
        coverage_reward = state.coverage / 100.0  # 0.0 to 1.0
        
        # Track stagnant coverage over iterations
        if not hasattr(state, 'stagnant_coverage_iterations'):
            state.stagnant_coverage_iterations = 0
        
        # NEW: Track if coverage is stagnant
        is_stagnant = False
        
        # Check for coverage improvement
        coverage_improvement = 0.0
        if parent_state and hasattr(parent_state, "coverage"):
            coverage_delta = state.coverage - parent_state.coverage
            if coverage_delta > 0:
                # Reset stagnant counter on improvement
                state.stagnant_coverage_iterations = 0
                coverage_improvement = coverage_delta / 5.0  # Increased scaling
            else:
                # Increment stagnant counter
                state.stagnant_coverage_iterations += 1
                if state.stagnant_coverage_iterations > 3:
                    is_stagnant = True
        

        # Business logic bug detection rewards
        business_logic_reward = 0.0     
        # Bug detection rewards
        bug_reward = 0.0
        if state.detected_bugs:
            for bug in state.detected_bugs:
                if hasattr(state, 'business_logic_analysis'):
                    for issue in state.business_logic_analysis.get('potential_bugs', []):
                        # If detected bug aligns with predicted business logic issue
                        if self._bug_matches_predicted_issue(bug, issue):
                            # Give major reward boost - this is a key success case!
                            business_logic_reward += 1.0 * issue.get('confidence', 0.5)
                            logger.info(f"Detected bug matches predicted business logic issue: +{business_logic_reward} reward")
                            break
            # Basic reward for any bugs
            bug_reward = 0.5
            
            
            # Bonus for logical bugs
            if state.has_bugs:
                logical_bug_count = state.count_logical_bugs()
                bug_reward += 0.4 * logical_bug_count  # Increased from 0.3
                
                # Extra bonus for certain high-value bug types
                for bug in state.logical_bugs:
                    bug_type = bug.get("bug_type", "")
                    if bug_type in ["boundary_error", "boolean_bug", "operator_logic"]:
                        bug_reward += 0.3
                    elif bug_type in ["resource_leak", "concurrency_issue", "state_corruption"]:
                        bug_reward += 0.4
        
        # Failure scenario coverage rewards - MAJOR CHANGES HERE
        failure_coverage_reward = 0.0
        if hasattr(state, "covered_failures"):
            # Get previous pattern coverage
            previous_pattern_count = 0
            if parent_state and hasattr(parent_state, "covered_failures"):
                previous_pattern_count = len(parent_state.covered_failures)
            
            current_pattern_count = len(state.covered_failures)
            
            # Base pattern coverage (as percentage of total)
            if self.failures:
                pattern_coverage_pct = current_pattern_count / len(self.failures)
                failure_coverage_reward += pattern_coverage_pct * 0.8
            
            # NEW: Major reward for new pattern discoveries
            new_patterns = current_pattern_count - previous_pattern_count
            if new_patterns > 0:
                # Reset stagnation counter when finding new patterns
                state.stagnant_coverage_iterations = 0
                
                # Significant reward for each new pattern discovered
                failure_coverage_reward += new_patterns * 0.6
                
                # Track which specific patterns were newly covered
                newly_covered = []
                if parent_state and hasattr(parent_state, "covered_failures"):
                    newly_covered = [p for p in state.covered_failures 
                                if p not in parent_state.covered_failures]
                
                # Extra reward for high risk patterns
                for pattern_id in newly_covered:
                    pattern_type = pattern_id.split('_')[0] if '_' in pattern_id else pattern_id
                    # Check if this is a high risk pattern
                    is_high_risk = any(p.get("risk_level") == "high" and 
                                    p.get("type") == pattern_type 
                                    for p in self.failures)
                    if is_high_risk:
                        failure_coverage_reward += 0.4
                        logger.info(f"Extra reward for covering high-risk pattern: {pattern_id}")
        
        # Branch condition rewards
        branch_reward = 0.0
        if hasattr(state, "covered_branch_conditions") and self.f_model:
            # Get previous branch coverage
            previous_branch_count = 0
            if parent_state and hasattr(parent_state, "covered_branch_conditions"):
                previous_branch_count = len(parent_state.covered_branch_conditions)
            
            current_branch_count = len(state.covered_branch_conditions)
            
            # Base branch coverage
            if hasattr(self.f_model, 'boundary_conditions') and self.f_model.boundary_conditions:
                covered_ratio = current_branch_count / len(self.f_model.boundary_conditions)
                branch_reward = covered_ratio * 0.5
                
            # NEW: Reward for newly covered branches
            new_branches = current_branch_count - previous_branch_count
            if new_branches > 0:
                # Additional reward for each new branch covered
                branch_reward += new_branches * 0.2
        
        # Test quality rewards
        quality_reward = 0.0
        
        # Reward test diversity
        if hasattr(state, "has_boundary_tests") and state.has_boundary_tests:
            quality_reward += 0.1
        if hasattr(state, "has_boolean_bug_tests") and state.has_boolean_bug_tests:
            quality_reward += 0.1
        if hasattr(state, "has_state_transition_tests") and state.has_state_transition_tests:
            quality_reward += 0.1
        if hasattr(state, "has_exception_path_tests") and state.has_exception_path_tests:
            quality_reward += 0.1
        
        # NEW: Exploration bonus for stagnant coverage
        exploration_bonus = 0.0
        if is_stagnant:
            # Add increasing exploration bonus based on stagnation length
            exploration_bonus = min(0.5, 0.1 * state.stagnant_coverage_iterations)
            logger.info(f"Adding exploration bonus of {exploration_bonus} after " +
                    f"{state.stagnant_coverage_iterations} stagnant iterations")
        
        # Combine rewards - adjust weights based on focus
        if self.focus_on_bugs:
            # When focusing on bugs, prioritize bug detection and logic coverage
            combined_reward = (
                0.2 * coverage_reward +
                0.15 * coverage_improvement +  # Increased from 0.1
                0.3 * bug_reward +
                0.20 * business_logic_reward +
                0.25 * failure_coverage_reward +  # Increased from 0.2
                0.05 * branch_reward +
                0.05 * quality_reward +
                exploration_bonus  # Add exploration bonus for stagnant coverage
            )
        else:
            # When focusing on coverage, adjust weights accordingly
            combined_reward = (
                0.35 * coverage_reward +
                0.2 * coverage_improvement +
                0.1 * bug_reward +
                0.2 * failure_coverage_reward +
                0.05 * branch_reward +
                0.05 * quality_reward +
                exploration_bonus  # Add exploration bonus for stagnant coverage
            )
        
        # Log detailed reward components for debugging
        if hasattr(self, 'current_iteration') and self.current_iteration % 5 == 0:
            logger.info(f"Reward components: coverage={coverage_reward:.2f}, " +
                    f"improvement={coverage_improvement:.2f}, bug={bug_reward:.2f}, " +
                    f"logic={failure_coverage_reward:.2f}, branch={branch_reward:.2f}, " +
                    f"quality={quality_reward:.2f}, exploration={exploration_bonus:.2f}")
        
        return combined_reward


    def _bug_matches_predicted_issue(self, bug, issue):
        """
        Check if a detected bug matches a predicted business logic issue
        
        Parameters:
        bug (dict): Detected bug
        issue (dict): Predicted business logic issue
        
        Returns:
        bool: True if match found
        """
        # Check method name match
        bug_method = bug.get("test_method", "")
        issue_method = issue.get("method", "")
        if not bug_method or not issue_method:
            return False
        
        # Simplify method name (remove "test" prefix)
        if bug_method.startswith("test"):
            simplified_bug_method = bug_method[4:]
        else:
            simplified_bug_method = bug_method
        
        # If methods don't match, not the same issue
        if issue_method.lower() not in simplified_bug_method.lower() and simplified_bug_method.lower() not in issue_method.lower():
            return False
        
        # Check error message for semantic similarity to issue description
        bug_error = bug.get("error", "") + " " + bug.get("description", "")
        issue_desc = issue.get("description", "")
        
        # Look for keywords from issue in bug error
        issue_keywords = set(re.findall(r'\b\w{4,}\b', issue_desc.lower()))
        if not issue_keywords:
            return False
        
        # Check how many issue keywords appear in the bug error
        error_text = bug_error.lower()
        matches = sum(1 for kw in issue_keywords if kw in error_text)
        
        # Return true if sufficient keyword matches
        return matches >= min(2, len(issue_keywords) // 2)


    def _create_bug_signature(self, bug_info):
        """
        create a unique bug signature for deduplication
        
        Parameters:
        bug_info (dict): bug information
        
        Returns:
        str: bug signature
        """
        import hashlib
        import re
        
        method_name = bug_info.get("test_method", "unknown")
        error_msg = bug_info.get("error", "")
        
        # clean the variable part of the error message (e.g. memory address)
        cleaned_error = re.sub(r'@[0-9a-f]+', '', error_msg)
        
        # extract the core information of the error
        if "expected:" in cleaned_error and "but was:" in cleaned_error:
            # assertion failure type error
            error_parts = re.search(r'expected:.*?<([^>]+)>.*?but was:.*?<([^>]+)>', cleaned_error)
            if error_parts:
                # only use the core part of the error to create the signature
                cleaned_error = f"expected:{error_parts.group(1)}_but_was:{error_parts.group(2)}"
        elif "Exception" in cleaned_error:
            # exception type error
            exception_type = re.search(r'([A-Za-z]+Exception)', cleaned_error)
            if exception_type:
                # use the exception type as the core part
                cleaned_error = exception_type.group(1)
        
        # the hash value of the method name and error core as the signature
        signature = f"{method_name}:{hashlib.md5(cleaned_error.encode()).hexdigest()[:12]}"
        return signature

    def _create_robust_bug_signature(self, bug_info):
        """
        create a more robust unique bug signature
        
        Parameters:
        bug_info (dict): bug information
        
        Returns:
        str: more robust bug signature
        """
        import hashlib
        import re
        
        method_name = bug_info.get("test_method", bug_info.get("method_name", "unknown"))
        bug_type = bug_info.get("bug_type", bug_info.get("bug_type", "unknown"))
        error_msg = bug_info.get("error", "")
        iteration = bug_info.get("found_in_iteration", 0)
        
        # clean the variable part of the error message (e.g. memory address, line number, etc.)
        cleaned_error = re.sub(r'@[0-9a-f]+', '', error_msg)
        cleaned_error = re.sub(r'line\s+\d+', 'line_num', cleaned_error)
        
        # extract the core information of the error, use different extraction strategies for different types of errors
        error_essence = ""
        if "expected:" in cleaned_error and "but was:" in cleaned_error:
            # assertion failure type error
            error_parts = re.search(r'expected:.*?<([^>]+)>.*?but was:.*?<([^>]+)>', cleaned_error)
            if error_parts:
                # only use the core part of the error to create the signature
                error_essence = f"expected:{error_parts.group(1)}_but_was:{error_parts.group(2)}"
        elif "Exception" in cleaned_error:
            # exception type error
            exception_type = re.search(r'([A-Za-z]+Exception)', cleaned_error)
            if exception_type:
                # use the exception type as the core part
                error_essence = exception_type.group(1)
        else:
            # other type error, use the first 50 characters
            error_essence = cleaned_error[:50]
        
        # combine the key information to create the signature
        signature_base = f"{method_name}_{bug_type}_{error_essence}"
        
        # add the iteration number to further distinguish
        if iteration > 0:
            signature_base += f"_iter{iteration}"
        
        # use the hash to create a fixed length signature
        signature = f"{method_name}_{hashlib.md5(signature_base.encode()).hexdigest()[:12]}"
        
        return signature

    def generate_test_summary(self):
        """
        generate a test summary, including more precise bug statistics
        
        Returns:
        dict: test summary dictionary
        """
        # get all the verified bugs, including real bugs and false positives
        all_verified_bugs = []
        real_bugs = []
        false_positives = []
        
        # first get all the bugs from the original verification results
        if hasattr(self, "original_verified_methods") and self.original_verified_methods:
            for bug in self.original_verified_methods:
                all_verified_bugs.append(bug)
                if bug.get("is_real_bug", False):
                    real_bugs.append(bug)
                else:
                    false_positives.append(bug)
        
        # then get the bugs from verified_bug_methods
        elif hasattr(self, "verified_bug_methods") and self.verified_bug_methods:
            for bug in self.verified_bug_methods:
                all_verified_bugs.append(bug)
                if bug.get("is_real_bug", False):
                    real_bugs.append(bug)
                else:
                    false_positives.append(bug)
        
        # if there are no bugs, get them from potential_bugs
        if not all_verified_bugs and hasattr(self, "potential_bugs"):
            for bug in self.potential_bugs:
                if bug.get("verified", False):
                    all_verified_bugs.append(bug)
                    if bug.get("is_real_bug", False):
                        real_bugs.append(bug)
                    else:
                        false_positives.append(bug)
        
        # analyze and count the bugs and test methods
        unique_bug_signatures = set()
        unique_test_methods = set()
        
        for bug in all_verified_bugs:
            method_name = bug.get("method_name", "")
            bug_type = bug.get("bug_type", bug.get("bug_type", "unknown"))
            signature = f"{method_name}_{bug_type}"
            
            if method_name:
                unique_test_methods.add(method_name)
            if signature:
                unique_bug_signatures.add(signature)
        
        # group by bug type
        bugs_by_type = {}
        for bug in all_verified_bugs:
            bug_type = bug.get("bug_type", bug.get("bug_type", "unknown"))
            if bug_type not in bugs_by_type:
                bugs_by_type[bug_type] = {"total": 0, "real": 0, "false_positive": 0}
            
            bugs_by_type[bug_type]["total"] += 1
            if bug.get("is_real_bug", False):
                bugs_by_type[bug_type]["real"] += 1
            else:
                bugs_by_type[bug_type]["false_positive"] += 1
        
        # check the coverage of the last test
        coverage = self.current_coverage
        
        # calculate the execution time
        execution_time = time.time() - getattr(self, "start_time", time.time())
        
        # get the bug types
        bug_types = set()
        if hasattr(self, "metrics") and "bug_types_found" in self.metrics:
            bug_types.update(self.metrics["bug_types_found"])
        
        # extract the unique types from all the bugs
        for bug in real_bugs:
            bug_type = bug.get("bug_type", bug.get("bug_type", "unknown"))
            if bug_type != "unknown":
                bug_types.add(bug_type)
        
        # if there are no bug types, add a default one
        if not bug_types and len(real_bugs) > 0:
            bug_types.add("failure_error")
        
        # record the number of real bugs and false positives
        real_bugs_count = len(real_bugs)
        false_positives_count = len(false_positives)
        
        logger.info(f"generate a test summary, found {real_bugs_count} real bugs and {false_positives_count} false positives")
        
        # update the bug count
        self.bugs_found = real_bugs_count
        
        # generate bug details (including all the verified bugs)
        bug_details = self._generate_bug_details()
        
        # generate bug trend
        bug_trend = self._generate_bug_trend()
        
        # generate coverage trend
        coverage_trend = self._generate_coverage_trend()
        
        # create a complete summary
        summary = {
            "class_name": self.class_name,
            "package_name": self.package_name,
            "best_coverage": round(coverage, 2) if isinstance(coverage, (int, float)) else 0.0,
            "has_errors": False,
            "iterations": len(self.history),
            "status": "Success" if coverage >= 90 and real_bugs_count > 0 else 
                    "Partial Success" if coverage >= 70 or real_bugs_count > 0 else "Failed",
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "bugs_found": real_bugs_count,  # update to the number of real bugs
            "false_positives_found": false_positives_count,  # add the number of false positives
            "bug_types": list(bug_types),
            "bugs_found_iteration": self._get_first_bug_iteration(),
            "bug_details": bug_details,  # include all the verified bugs, including real bugs and false positives
            "history": self.history,
            "coverage_trend": coverage_trend,
            "bug_trend": bug_trend,
            "performance_stats": {
                "avg_execution_time": round(execution_time / max(1, len(self.history)), 2),
                "max_execution_time": round(execution_time, 2),
                "coverage_improvement_rate": self._calculate_coverage_improvement_rate(),
                "final_iterations": len(self.history),
                "real_bugs_to_false_positives_ratio": round(real_bugs_count / max(1, false_positives_count), 2)
            }
        }
                
        return summary


    def _get_first_bug_iteration(self):
        """get the iteration number of the first bug"""
        for i, entry in enumerate(self.history):
            # Check if detected_bugs is a list or an integer
            detected_bugs = entry.get("detected_bugs", 0)
            bugs_found = entry.get("bugs_found", 0)
            
            # Handle detected_bugs being a list
            if isinstance(detected_bugs, list):
                detected_bugs_count = len(detected_bugs)
            else:
                detected_bugs_count = detected_bugs
                
            # Handle bugs_found being a list
            if isinstance(bugs_found, list):
                bugs_found_count = len(bugs_found)
            else:
                bugs_found_count = bugs_found
                
            if detected_bugs_count > 0 or bugs_found_count > 0:
                return entry.get("iteration", i+1)
        return None
        
    def _generate_bug_details(self):
        """
        generate a complete bug details list, including the iteration number of each bug
        include all the verified bugs, including real bugs and false positives
        """
        bug_details = []
        processed_methods = set()  # track the processed methods
        
        # check if there are original verification results
        if hasattr(self, "original_verified_methods") and self.original_verified_methods:
            logger.info(f"从 original_verified_methods 中生成 bug 详情，共有 {len(self.original_verified_methods)} 个方法")
            # print("--------------------------------")
            # print("original_verified_methods")
            # print("--------------------------------")
            for bug in self.original_verified_methods:
                # print(bug)
                # print("--------------------------------")
                
                method_name = bug.get("method_name", "")
                if not method_name or method_name in processed_methods:
                    continue
                
                # include all the verified bugs, whether they are real bugs or false positives
                is_real_bug = bug.get("is_real_bug", False)
                # iteration = bug.get("found_in_iteration", 0)
                iteration = 0
                if bug.get("bug_info") and isinstance(bug["bug_info"], list) and len(bug["bug_info"]) > 0:
                    first_bug_info = bug["bug_info"][0]
                    iteration = first_bug_info.get("found_in_iteration", 0)
                else:
                    iteration = bug.get("found_in_iteration", 0)
                bug_type = bug.get("bug_type", bug.get("bug_type", "unknown"))

                bug_details.append({
                    "iteration": iteration,
                    "method": method_name,
                    "type": bug_type,
                    "verified": True,
                    "is_real_bug": is_real_bug
                })
                processed_methods.add(method_name)
                logger.info(f"添加bug: {method_name}, 类型: {bug_type}, 是真实bug: {is_real_bug}")
        
        # get the verified bugs from verified_bug_methods
        if hasattr(self, "verified_bug_methods") and self.verified_bug_methods:
            logger.info(f"从 verified_bug_methods 中生成 bug 详情，共有 {len(self.verified_bug_methods)} 个方法")
            for bug in self.verified_bug_methods:
                # print(bug)
                method_name = bug.get("method_name", "")
                if not method_name or method_name in processed_methods:
                    continue
                
                # include all the verified bugs, whether they are real bugs or false positives
                is_real_bug = bug.get("is_real_bug", False)
                iteration = 0
                if bug.get("bug_info") and isinstance(bug["bug_info"], list) and len(bug["bug_info"]) > 0:
                    first_bug_info = bug["bug_info"][0]
                    iteration = first_bug_info.get("found_in_iteration", 0)
                else:
                    iteration = bug.get("found_in_iteration", 0)
                bug_type = bug.get("bug_type", bug.get("bug_type", "unknown"))

                bug_details.append({
                    "iteration": iteration,
                    "method": method_name,
                    "type": bug_type,
                    "verified": True,
                    "is_real_bug": is_real_bug
                })
                processed_methods.add(method_name)
                logger.info(f"添加bug: {method_name}, 类型: {bug_type}, 是真实bug: {is_real_bug}")
        
        # add the extra verified bugs from potential_bugs
        if hasattr(self, "potential_bugs") and self.potential_bugs:
            logger.info(f"从 potential_bugs 中生成 bug 详情，共有 {len(self.potential_bugs)} 个方法")
            for bug in self.potential_bugs:
                test_method = bug.get("test_method", "")
                if not test_method or test_method in processed_methods:
                    continue
                
                # include all the verified bugs, whether they are real bugs or false positives
                if bug.get("verified", False):
                    iteration = 0
                    if bug.get("bug_info") and isinstance(bug["bug_info"], list) and len(bug["bug_info"]) > 0:
                        first_bug_info = bug["bug_info"][0]
                        iteration = first_bug_info.get("found_in_iteration", 0)
                    else:
                        iteration = bug.get("found_in_iteration", 0)
                    bug_type = bug.get("bug_type", "unknown")
                    is_real_bug = bug.get("is_real_bug", False)
                    
                    bug_details.append({
                        "iteration": iteration,
                        "method": test_method,
                        "type": bug_type,
                        "verified": True,
                        "is_real_bug": is_real_bug
                    })
                    processed_methods.add(test_method)
                    logger.info(f"添加bug: {test_method}, 类型: {bug_type}, 是真实bug: {is_real_bug}")
        
        # calculate the number of real bugs and false positives for logging
        real_bugs = len([b for b in bug_details if b.get("is_real_bug", False)])
        false_positives = len([b for b in bug_details if not b.get("is_real_bug", False)])
        logger.info(f"最终生成 {len(bug_details)} 个验证后的bug详情，其中 {real_bugs} 个真实bug，{false_positives} 个误报")
        return bug_details


    def save_test_summary(self):
        """
        generate and save the test summary to a JSON file
        
        Returns:
        str: the path of the test summary file
        """
        try:
            # ensure that the verified and collected bugs are counted correctly
            if hasattr(self, "potential_bugs") and self.potential_bugs:
                logger.info(f"Checking {len(self.potential_bugs)} potential bugs before generating test summary")
                for bug in self.potential_bugs:
                    method_name = bug.get("method_name", bug.get("test_method", "unknown"))
                    is_real_bug = bug.get("is_real_bug", False)
                    verified = bug.get("verified", False)
                    
                    # if it is a verified real bug, ensure it is added to verified_bug_methods
                    if verified and is_real_bug and method_name != "unknown":
                        if not hasattr(self, "verified_bug_methods"):
                            self.verified_bug_methods = []
                            
                        # check if it is already in verified_bug_methods
                        if not any(b.get("method_name") == method_name for b in self.verified_bug_methods):
                            logger.info(f"Adding verified real bug to summary: {method_name}")
                            # fix: ensure the method_name field is correctly assigned
                            bug["method_name"] = method_name
                            self.verified_bug_methods.append(bug)
            
            # generate the test summary
            test_summary = self.generate_test_summary()
            
            # ensure that bug_trend and coverage_trend exist
            if "bug_trend" not in test_summary:
                logger.warning("Bug trend missing in test summary, adding empty list")
                test_summary["bug_trend"] = []
                
            if "coverage_trend" not in test_summary:
                logger.warning("Coverage trend missing in test summary, adding empty list")
                test_summary["coverage_trend"] = []
                
            # calculate the number of real bugs and record the log
            real_bugs_count = len([b for b in self.verified_bug_methods if b.get("is_real_bug", False) is True]) if hasattr(self, "verified_bug_methods") else 0
            logger.info(f"Found {real_bugs_count} real bugs in total")
                
            # ensure that bugs_found reflects the number of real bugs
            test_summary["bugs_found"] = real_bugs_count
            
            # Determine output filename
            class_name = self.class_name
            summary_file = os.path.join(self.project_dir, f"{class_name}_test_summary.json")
            
            # Save the summary
            with open(summary_file, 'w', encoding='utf-8') as f:
                json.dump(test_summary, f, indent=2)
                
            logger.info(f"Enhanced test summary with {real_bugs_count} bugs saved to: {summary_file}")
            return summary_file
        except Exception as e:
            logger.error(f"Failed to save test summary: {str(e)}")
            logger.error(traceback.format_exc())
            
            # try to create the minimal valid summary
            try:
                minimal_summary = {
                    "class_name": self.class_name,
                    "package_name": self.package_name,
                    "best_coverage": getattr(self, "current_coverage", 0.0),
                    "has_errors": True,
                    "iterations": len(self.history) if hasattr(self, "history") else 0,
                    "status": "Error",
                    "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "error_message": str(e)
                }
                
                summary_file = os.path.join(self.project_dir, f"{self.class_name}_test_summary.json")
                with open(summary_file, 'w', encoding='utf-8') as f:
                    json.dump(minimal_summary, f, indent=2)
                
                logger.info(f"Saved minimal test summary due to error: {summary_file}")
                return summary_file
            except Exception as e2:
                logger.error(f"Failed to save even minimal summary: {str(e2)}")
                return None
        

    def generate_integrated_test_code(self):
        """
        generate the integrated test code that includes all the verified bugs
        
        Returns:
        str: the complete test code that integrates all the valid bugs
        """
        logger.info(f"generate the integrated test code, merge {len(self.verified_bug_methods)} verified bugs")
        
        # find the test case with the highest coverage as the base
        highest_coverage_test = self.best_test
        highest_coverage = self.current_coverage if hasattr(self, "current_coverage") else 0.0
        
        # find the test case with the highest coverage from the high coverage tests dictionary
        if hasattr(self, "high_coverage_tests") and self.high_coverage_tests:
            best_coverage_key = None
            best_coverage_value = 0.0
            
            for coverage_str, test_code in self.high_coverage_tests.items():
                try:
                    coverage_value = float(coverage_str.replace("_", "."))
                    if coverage_value > best_coverage_value:
                        best_coverage_value = coverage_value
                        best_coverage_key = coverage_str
                except:
                    continue
            
            if best_coverage_key and best_coverage_value > highest_coverage:
                highest_coverage = best_coverage_value
                highest_coverage_test = self.high_coverage_tests[best_coverage_key]
                logger.info(f"select the test code with coverage {highest_coverage:.2f}% from the high coverage tests dictionary")
        
        # find the test case with the highest coverage from the history
        if hasattr(self, "history") and self.history:
            for entry in self.history:
                if entry.get("coverage", 0.0) > highest_coverage and "test_code" in entry and entry["test_code"]:
                    highest_coverage = entry.get("coverage", 0.0)
                    highest_coverage_test = entry["test_code"]
                    logger.info(f"select the test code with coverage {highest_coverage:.2f}% from the history")
        
        logger.info(f"select the test code with coverage {highest_coverage:.2f}% as the base")
        
        # if there are no verified bugs, return the test code with the highest coverage
        if not self.verified_bug_methods:
            return highest_coverage_test
            
        # use the test code with the highest coverage as the base
        base_test_code = highest_coverage_test
        
        # execute more strict bug deduplication and filtering
        real_bugs = [bug for bug in self.verified_bug_methods if bug.get("is_real_bug", False)]
        logger.info(f"after filtering, there are {len(real_bugs)} real bugs left")
        
        if len(real_bugs) == 0:
            logger.warning("after filtering, there are no real bugs to integrate, return the test code with the highest coverage")
            return highest_coverage_test
        
        # extract the test class name and package name
        import re
        class_pattern = r"public\s+class\s+(\w+)"
        package_pattern = r"package\s+([\w.]+);"
        
        class_match = re.search(class_pattern, base_test_code)
        package_match = re.search(package_pattern, base_test_code)
        
        test_class_name = class_match.group(1) if class_match else "IntegratedTest"
        package_name = package_match.group(1) if package_match else self.package_name
        
        # find the class end position for insertion
        class_end = base_test_code.rfind('}')
        if class_end == -1:
            class_end = len(base_test_code)
        
        # collect all the methods to add
        added_methods = []
        added_methods_names = set()
        
        # save all the test codes to improve the chance of method extraction
        all_test_codes = set()
        all_test_codes.add(base_test_code)
        
        # add all the test codes from the history
        if hasattr(self, "history") and self.history:
            for entry in self.history:
                if "test_code" in entry and entry["test_code"]:
                    all_test_codes.add(entry["test_code"])
        
        # add the test codes from the high coverage tests
        if hasattr(self, "high_coverage_tests") and self.high_coverage_tests:
            for test_code in self.high_coverage_tests.values():
                all_test_codes.add(test_code)
        
        # process each verified real bug test method
        for i, bug in enumerate(real_bugs):
            method_name = bug.get("method_name", "")
            if not method_name or method_name in added_methods_names:
                logger.warning(f"skip the method {method_name}: the name is empty or duplicated")
                continue
                
            method_code = bug.get("code", "")
            
            # if there is no code, try to extract from multiple sources
            if not method_code:
                # 1. get the code from the method_code field
                if bug.get("method_code"):
                    method_code = bug["method_code"]
                    logger.info(f"find the method code from the method_code field: {method_name}")
                # 2. extract the code from the test codes
                else:
                    for test_code in all_test_codes:
                        extracted_code = self._extract_method_from_test_code(test_code, method_name)
                        if extracted_code:
                            method_code = extracted_code
                            logger.info(f"extract the method code from the test codes: {method_name}")
                            break
                
                # 3. if still not found, check all the states
                if not method_code and hasattr(self, "all_states"):
                    for state in self.all_states:
                        if hasattr(state, "test_code") and state.test_code:
                            extracted_code = self._extract_method_from_test_code(state.test_code, method_name)
                            if extracted_code:
                                method_code = extracted_code
                                logger.info(f"extract the method code from the states: {method_name}")
                                break
                
                # 4. finally try to extract from the final test code
                if not method_code:
                    method_code = self._extract_method_from_test_code(base_test_code, method_name)
                    if method_code:
                        logger.info(f"extract the method code from the base test code: {method_name}")
            
            # if still not found, create a placeholder test method
            if not method_code:
                logger.warning(f"cannot find the method code for {method_name}, create a placeholder test method")
                method_code = f"""
        @Test
        public void {method_name}() {{
            // TODO: This is a placeholder for a real bug found during testing
            // Bug type: {bug.get('bug_type', bug.get('bug_type', 'unknown'))}
            // Please implement this test case
            fail("Test not implemented but a real bug was found here");
        }}
    """
                    
            # ensure the method name is not duplicated
            original_method_name = method_name
            counter = 1
            while method_name in added_methods_names:
                method_name = f"{original_method_name}_{counter}"
                counter += 1
                
            added_methods_names.add(method_name)
            
            # if needed, modify the method name
            if original_method_name != method_name:
                method_code = method_code.replace(f"public void {original_method_name}", f"public void {method_name}")
                
            # add the method code and comment
            bug_type = bug.get("bug_type", bug.get("bug_type", "unknown"))
            verification_confidence = bug.get("verification_confidence", 0.0)
            
            bug_method_with_comment = f"""
        /**
        * Bug test: {method_name}
        * Bug type: {bug_type}
        * Verification confidence: {verification_confidence:.2f}
        */
    {method_code}
    """
            added_methods.append(bug_method_with_comment)
            logger.info(f"add the method {method_name} to the integrated test code")
        
        # if there are no valid methods, return the original code
        if not added_methods:
            logger.warning("there are no valid bug test methods to add, return the test code with the highest coverage")
            return highest_coverage_test
            
        # check if the base test code already contains some method names
        for method_name in list(added_methods_names):
            pattern = r"public\s+void\s+" + re.escape(method_name) + r"\s*\("
            if re.search(pattern, base_test_code):
                logger.info(f"the base test code already contains the method {method_name}, skip it")
                for i, method in enumerate(added_methods):
                    if f"public void {method_name}" in method:
                        added_methods.pop(i)
                        break
        
        # insert all the methods before the class end
        integrated_code = (
            base_test_code[:class_end] + 
            "\n    // ===== automatically generated bug test methods ===== \n" +
            "".join(added_methods) +
            base_test_code[class_end:]
        )
        
        # add the necessary imports
        if "@Test" not in integrated_code:
            import_pos = integrated_code.find(";") + 1
            integrated_code = (
                integrated_code[:import_pos] + 
                "\n\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;" +
                integrated_code[import_pos:]
            )
            
        # ensure that the Exception import is present
        if "throws Exception" in integrated_code and "import java.lang.Exception;" not in integrated_code:
            import_pos = integrated_code.find(";") + 1
            integrated_code = (
                integrated_code[:import_pos] + 
                "\nimport java.lang.Exception;" +
                integrated_code[import_pos:]
            )
        
        logger.info(f"successfully generate the integrated test code, added {len(added_methods)} bug test methods, based on the test code with coverage {highest_coverage:.2f}%")
        
        # check and fix the compilation issues
        logger.info("check if the integrated test code has compilation issues...")
        is_valid, fixed_code = self.verify_integrated_test_compilation(integrated_code)
        
        if not is_valid:
            logger.warning("the integrated test code has compilation issues, try to fix it")
            return fixed_code
            
        return integrated_code


    def verify_integrated_test_compilation(self, test_code):
        """
        verify if the integrated test code can be compiled, if not, try to fix it
        
        Parameters:
        test_code (str): the integrated test code
        
        Returns:
        tuple: (is_valid, fixed_code) - whether the code is valid, the fixed code
        """
        logger.info("verify if the integrated test code can be compiled...")
        
        # add the missing helper methods
        test_code = self._add_missing_helper_methods(test_code)
        
        # save the test code to a temporary file
        test_file = save_test_code(
            test_code, 
            self.class_name, 
            self.package_name, 
            self.project_dir
        )
        
        # try to compile the test
        max_attempts = 3
        current_test = test_code
        
        for attempt in range(1, max_attempts + 1):
            logger.info(f"compilation attempt #{attempt}")
            
            # save the current version of the test code
            save_test_code(
                current_test, 
                self.class_name, 
                self.package_name, 
                self.project_dir
            )
            
            # run the tests to check the compilation errors
            _, _, _, compilation_errors = run_tests_with_jacoco(
                self.project_dir, 
                self.class_name, 
                self.package_name, 
                f"{self.package_name}.{self.class_name}Test",
                False,
                getattr(self, 'project_type', 'maven')
            )
            
            # if there are no compilation errors, return success
            if not compilation_errors:
                logger.info("the integrated test code compiled successfully!")
                return True, current_test
                
            logger.warning(f"the integrated test code has compilation errors: {compilation_errors[:2]}")
            
            # extract the compilation error types
            duplicate_var_errors = sum(1 for err in compilation_errors if "variable" in err and "already defined" in err)
            symbol_errors = sum(1 for err in compilation_errors if "cannot find symbol" in err)
            
            # if the main issue is variable duplication, try to enhance variable renaming
            if duplicate_var_errors > 0:
                logger.info(f"detected {duplicate_var_errors} variable duplication errors, try to enhance variable renaming")
                
                # try to apply more aggressive variable renaming
                import re
                
                # extract each test method and process it separately
                method_pattern = r'(public\s+void\s+test\w+\s*\([^)]*\)\s*(?:throws\s+[^{]+)?\s*\{[^}]*\})'
                test_methods = re.finditer(method_pattern, current_test)
                
                # store the renamed methods
                renamed_methods = []
                used_vars = set()
                
                for i, method_match in enumerate(test_methods):
                    method_code = method_match.group(1)
                    renamed_code, new_vars = self._rename_variables(method_code, used_vars, i * 100 + attempt)
                    renamed_methods.append(renamed_code)
                    used_vars.update(new_vars)
                
                # if the methods are found and renamed
                if renamed_methods:
                    # rebuild the code, preserving the class declaration and member variables
                    class_start = current_test.find("public class")
                    class_body_start = current_test.find("{", class_start) + 1
                    
                    # find the start position of the first test method
                    first_test_method = re.search(r'public\s+void\s+test\w+\s*\(', current_test)
                    if first_test_method:
                        first_method_start = first_test_method.start()
                        # extract the class header (including member variables)
                        class_header = current_test[class_start:first_method_start]
                        
                        # rebuild the code
                        fixed_code = (
                            current_test[:class_start] + 
                            class_header +
                            "\n    ".join(renamed_methods) +
                            "\n}" # close the class
                        )
                        
                        # add the missing helper methods
                        fixed_code = self._add_missing_helper_methods(fixed_code)
                        
                        current_test = fixed_code
                        continue
            
            # use LLM to fix the compilation errors
            fixed_code = self.fix_integrated_test_with_llm(current_test, compilation_errors)
            
            # if LLM cannot modify the code, try the next repair method
            if fixed_code == current_test:
                logger.warning("LLM cannot fix the code, try the next repair method")
                
                # if this is the last attempt, give up and return the original best test code
                if attempt == max_attempts:
                    logger.error("cannot fix the integrated test code, give up and return the original best test code")
                    return False, self.best_test
            else:
                # use the repaired code to continue
                current_test = fixed_code
                logger.info("use the repaired code to continue")
        
        # after the maximum number of attempts, still not fully repaired
        logger.warning(f"after {max_attempts} attempts, still not fully repaired")
        
        # return the last repaired version
        return False, current_test

    def fix_integrated_test_with_llm(self, test_code, error_message=None):
        """
        use LLM to fix the compilation issues in the integrated test code
        
        Parameters:
        test_code (str): the test code
        error_message (list): the error message list, if any
        
        Returns:
        str: the repaired test code
        """
        logger.info("try to use LLM to fix the compilation issues in the integrated test code")
        
        # create the prompt - focus on clearly defining the LLM's task and providing all necessary context
        prompt = f"""please help me fix the compilation issues in the following JUnit test code. your task is to identify issues such as undeclared variables, missing imports, method conflicts, and provide complete repaired code. i need the complete code, not just the repaired parts.

CRITICAL ANTI-PLACEHOLDER REQUIREMENTS:
i need the complete test class, including all original methods, not just the repaired parts.
your answer must include:
1. all package declarations
2. all import statements 
3. complete class definition
4. all existing test methods, not just the repaired ones
5. all fields and setup methods

ABSOLUTELY FORBIDDEN SHORTCUTS:
- do not use placeholders like "// all existing test methods remain unchanged..."
- do not use "// [previous test methods remain unchanged...]"
- do not use "// ... existing code ..."
- do not use "// [Previous imports remain exactly the same]"
- do not use ANY comments that indicate omitted code
- you must include the original code of all actual code, even if it is not changed
- do not accept shortcuts, abbreviations, or comments indicating that code is omitted
- i need the complete code that can be saved to a file and compiled directly

format your entire answer as a complete, compilable Java file that can be saved and run directly.

class information:
- class name: {self.class_name}
- package name: {self.package_name}

source code:
```java
{self.source_code} 
```

test code:
```java
{test_code}
```

compilation errors:
```
{error_message if error_message else "compilation failed, please check possible issues"}
```

please pay special attention to the following points:
1. check variable declarations and initializations - variables may need to be redeclared in different test methods
2. ensure that integrated test methods do not have variable name conflicts
3. ensure that all necessary imports exist
4. fix method signatures or parameter issues
5. ensure that variable scope is correct within methods

only fix necessary compilation issues, while preserving the original functionality of the test methods.
"""

        # call the LLM API
        try:
            api_response = call_anthropic_api(prompt)
            # api_response = call_deepseek_api(prompt)
            
            if not api_response or len(api_response) < 100:  # ensure that there is a sufficient response
                logger.warning("LLM响应不充分，尝试使用备用API")
                api_response = call_gpt_api(prompt)
                
            # extract the Java code
            from feedback import extract_java_code
            fixed_code = extract_java_code(api_response)
            
            if not fixed_code or len(fixed_code) < 100:
                logger.warning("cannot extract valid Java code from the LLM response")
                return test_code  # 返回原始代码
                
            logger.info("LLM successfully repaired the integrated test code")
            return fixed_code
            
        except Exception as e:
            logger.error(f"error when calling the LLM API: {str(e)}")
            return test_code  # 出错时返回原始代码
        

   
