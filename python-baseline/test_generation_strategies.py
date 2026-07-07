#!/usr/bin/env python3
"""
Test Generation Strategies for Logic-Aware Testing

This module provides strategies for generating tests specifically designed to
detect logical bugs and other defects. It includes a strategy selector that
chooses appropriate strategies based on code analysis.
"""

import re
import logging
import random
from collections import defaultdict

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("test_generation_strategies")

class LogicTestStrategy:
    """
    Strategy for generating logic-aware tests.
    
    Defines an approach to testing that targets specific types of logical
    vulnerabilities or code patterns.
    """
    
    def __init__(self, strategy_id, name, description, weight=1.0, target_bugs=None):
        """
        Initialize the strategy
        
        Parameters:
        strategy_id (str): Unique identifier for the strategy
        name (str): Human-readable name
        description (str): Description of the strategy
        weight (float): Initial weight/priority (higher = more important)
        target_bugs (list): Types of bugs this strategy targets
        """
        self.id = strategy_id
        self.name = name
        self.description = description
        self.weight = weight
        self.target_bugs = target_bugs or []
        
        # Track effectiveness metrics
        self.times_used = 0
        self.bugs_found = 0
        self.coverage_gained = 0.0
        
        # Track patterns and conditions this strategy is good at finding
        self.effective_patterns = set()
        self.effective_conditions = set()
    
    def adjust_weight(self, success_rate):
        """
        Adjust strategy weight based on success rate
        
        Parameters:
        success_rate (float): Success rate (0.0 to 1.0)
        """
        # Update weight based on success
        if success_rate > 0.5:
            # Increase weight for successful strategies
            self.weight = min(3.0, self.weight * 1.2)
        elif success_rate < 0.2:
            # Decrease weight for unsuccessful strategies
            self.weight = max(0.1, self.weight * 0.8)
    
    def calculate_success_rate(self):
        """
        Calculate success rate of this strategy
        
        Returns:
        float: Success rate (0.0 to 1.0)
        """
        if self.times_used == 0:
            return 0.0
        return self.bugs_found / self.times_used
    
    def record_usage(self, bugs_found=0, coverage_gain=0.0):
        """
        Record usage of this strategy
        
        Parameters:
        bugs_found (int): Number of bugs found
        coverage_gain (float): Coverage gained
        """
        self.times_used += 1
        self.bugs_found += bugs_found
        self.coverage_gained += coverage_gain
        
        # Adjust weight based on results
        if self.times_used >= 5:
            success_rate = self.calculate_success_rate()
            self.adjust_weight(success_rate)
    
    def add_effective_pattern(self, pattern_id):
        """
        Record a pattern this strategy is effective at finding
        
        Parameters:
        pattern_id (str): Pattern identifier
        """
        self.effective_patterns.add(pattern_id)
    
    def add_effective_condition(self, condition_id):
        """
        Record a condition this strategy is effective at testing
        
        Parameters:
        condition_id (str): Condition identifier
        """
        self.effective_conditions.add(condition_id)
    
    def to_dict(self):
        """
        Convert strategy to dictionary
        
        Returns:
        dict: Strategy as dictionary
        """
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "weight": self.weight,
            "target_bugs": self.target_bugs,
            "times_used": self.times_used,
            "bugs_found": self.bugs_found,
            "success_rate": self.calculate_success_rate(),
            "effective_patterns": list(self.effective_patterns),
            "effective_conditions": list(self.effective_conditions)
        }

class TestStrategySelector:
    """
    Selects appropriate test generation strategies based on code analysis.
    
    Uses information from logic model and detected patterns to choose
    effective test strategies.
    """
    
    def __init__(self, failures=None, f_model=None):
        """
        Initialize strategy selector
        
        Parameters:
        failures (list): Detected logic bug patterns
        f_model (Extractor): Logic model extracted from code
        """
        self.failures = failures or []
        self.f_model = f_model
        
        # Initialize strategies
        strategies_list = self._initialize_strategies()
        # 将策略列表转换为字典，以便 select_strategies 方法可以使用 .items()
        self.strategies = {strategy.id: strategy for strategy in strategies_list}
        
        # Track strategy usage and effectiveness
        self.strategy_usage = defaultdict(int)
        self.strategy_effectiveness = defaultdict(lambda: {"bugs": 0, "coverage": 0.0})
        
        # Target mappings
        self.pattern_to_strategies = defaultdict(list)
        self.condition_to_strategies = defaultdict(list)
        
        # Create pattern and condition mappings
        self._map_patterns_to_strategies()
        self._map_conditions_to_strategies()
        
        logger.info(f"Initialized strategy selector with {len(self.strategies)} strategies")
    
    def _initialize_strategies(self):
        """
        Initialize the set of test generation strategies
        
        Returns:
        list: List of strategies
        """
        strategies = [
            LogicTestStrategy(
                "boundary_testing",
                "Boundary Value Testing",
                "Tests at and around boundary conditions",
                weight=1.0,
                target_bugs=["boundary_error", "off_by_one", "index_error"]
            ),
            LogicTestStrategy(
                "expression",
                "Logical Expression Testing",
                "Tests complex logical expressions and conditionals",
                weight=1.0,
                target_bugs=["boolean_bug", "operator_logic"]
            ),
            LogicTestStrategy(
                "exception_handling",
                "Exception Path Testing",
                "Tests exception handling paths",
                weight=0.8,
                target_bugs=["exception_handling", "null_reference"]
            ),
            LogicTestStrategy(
                "data_validation",
                "Data Validation Testing",
                "Tests data validation and boundary checks",
                weight=0.8,
                target_bugs=["validation_error", "input_validation"]
            ),
            LogicTestStrategy(
                "string_operation_testing",
                "String Operation Testing",
                "Tests string operations for index out of bounds issues",
                weight=1.0,
                target_bugs=["string_index_bounds", "string_index_error"]
            ),
            LogicTestStrategy(
                "array_operation_testing",
                "Array Operation Testing",
                "Tests array operations for index out of bounds issues",
                weight=1.0,
                target_bugs=["array_index_bounds", "array_index_error", "off_by_one"]
            ),
            
            # New strategies for resource management defects
            LogicTestStrategy(
                "resource_lifecycle_testing",
                "Resource Lifecycle Testing",
                "Tests proper acquisition and release of resources",
                weight=0.9,
                target_bugs=["resource_leak", "resource_management", "use_after_close"]
            ),
            LogicTestStrategy(
                "exception_resource_testing",
                "Exception-Safe Resource Testing",
                "Tests resource cleanup during exceptional conditions",
                weight=0.9,
                target_bugs=["resource_leak", "exception_safety"]
            ),
            
            # New strategies for data operation issues
            LogicTestStrategy(
                "type_conversion_testing",
                "Type Conversion Testing",
                "Tests boundary cases in type conversions",
                weight=0.8,
                target_bugs=["data_operation", "integer_truncation", "precision_loss"]
            ),
            LogicTestStrategy(
                "arithmetic_edge_testing",
                "Arithmetic Edge Cases Testing",
                "Tests arithmetic operations with edge values",
                weight=0.8,
                target_bugs=["data_operation", "integer_overflow", "division_by_zero"]
            ),
            
            # New strategies for concurrency issues
            LogicTestStrategy(
                "concurrency_testing",
                "Concurrent Execution Testing",
                "Tests behavior under concurrent access",
                weight=0.7,
                target_bugs=["concurrency", "race_condition", "deadlock"]
            ),
            
            # Enhanced exception handling testing
            LogicTestStrategy(
                "error_propagation_testing",
                "Error Propagation Testing",
                "Tests correct propagation and handling of errors",
                weight=0.8,
                target_bugs=["exception_handling", "swallowed_exception", "empty_catch"]
            ),
            
            # Security-focused testing
            LogicTestStrategy(
                "security_testing",
                "Security Vulnerability Testing",
                "Tests for security vulnerabilities",
                weight=0.9,
                target_bugs=["security", "sql_injection", "hardcoded_credential"]
            ),
            
            # Input validation testing
            LogicTestStrategy(
                "null_empty_testing",
                "Null and Empty Input Testing",
                "Tests handling of null and empty inputs",
                weight=1.0,
                target_bugs=["validation", "null_pointer", "empty_collection"]
            ),
            
            # Random exploration strategy
            LogicTestStrategy(
                "random_exploration",
                "Random Exploration Testing",
                "Explores random aspects of code for potential issues",
                weight=0.5,
                target_bugs=[]
            ),

            # Strategies used by select_strategies; registered here so
            # record_strategy_result / get_strategy_statistics can track them
            LogicTestStrategy(
                "resource_management",
                "Resource Management Testing",
                "Tests acquisition, release and reuse of resources",
                weight=0.5,
                target_bugs=["resource_leak", "resource_management", "use_after_close"]
            ),
            LogicTestStrategy(
                "state_transition",
                "State Transition Testing",
                "Tests object state changes and transition sequences",
                weight=0.8,
                target_bugs=["state_error", "inconsistent_state"]
            ),
            LogicTestStrategy(
                "business_logic",
                "Business Logic Testing",
                "Tests semantic intent vs implementation mismatches",
                weight=0.0,
                target_bugs=["business_logic_error", "semantic_mismatch"]
            )
        ]

        #logic bug patterns
        # strategies = [
        #     LogicTestStrategy(
        #         "boundary_testing",
        #         "Boundary Value Testing",
        #         "Tests at and around boundary conditions",
        #         weight=1.0,
        #         target_bugs=["boundary_error", "off_by_one", "index_error"]
        #     ),
        #     LogicTestStrategy(
        #         "expression",
        #         "Logical Expression Testing",
        #         "Tests complex logical expressions and conditionals",
        #         weight=1.0,
        #         target_bugs=["boolean_bug", "operator_logic"]
        #     ),
        #     LogicTestStrategy(
        #         "data_validation",
        #         "Data Validation Testing",
        #         "Tests data validation and boundary checks",
        #         weight=0.8,
        #         target_bugs=["validation_error", "input_validation"]
        #     ),
        #     LogicTestStrategy(
        #         "string_operation_testing",
        #         "String Operation Testing",
        #         "Tests string operations for index out of bounds issues",
        #         weight=1.0,
        #         target_bugs=["string_index_bounds", "string_index_error"]
        #     ),
        #     LogicTestStrategy(
        #         "array_operation_testing",
        #         "Array Operation Testing",
        #         "Tests array operations for index out of bounds issues",
        #         weight=1.0,
        #         target_bugs=["array_index_bounds", "array_index_error", "off_by_one"]
        #     ),
        #     LogicTestStrategy(
        #         "type_conversion_testing",
        #         "Type Conversion Testing",
        #         "Tests boundary cases in type conversions",
        #         weight=0.8,
        #         target_bugs=["data_operation", "integer_truncation", "precision_loss"]
        #     ),
        #     LogicTestStrategy(
        #         "arithmetic_edge_testing",
        #         "Arithmetic Edge Cases Testing",
        #         "Tests arithmetic operations with edge values",
        #         weight=0.8,
        #         target_bugs=["data_operation", "integer_overflow", "division_by_zero"]
        #     ),
        #     LogicTestStrategy(
        #         "null_empty_testing",
        #         "Null and Empty Input Testing",
        #         "Tests handling of null and empty inputs",
        #         weight=1.0,
        #         target_bugs=["validation", "null_pointer", "empty_collection"]
        #     )
        # ]
        
        return strategies
    
    def _map_patterns_to_strategies(self):
        """Map detected patterns to relevant strategies"""
        # Clear existing mappings
        self.pattern_to_strategies = defaultdict(list)
        
        # Define pattern-to-strategy mappings
        pattern_mappings = {
            # Logical bug patterns
            "operator_precedence": ["expression"],
            "bitwise_logical_confusion": ["expression"],
            "off_by_one": ["boundary_testing"],
            "boundary_condition": ["boundary_testing"],
            "null_handling": ["null_empty_testing", "exception_handling"],
            "string_comparison": ["data_validation"],
            "boolean_bug": ["expression"],

            # 新增字符串索引相关映射
            "string_index_bounds": ["string_operation_testing", "boundary_testing"],
            "string_index_error": ["string_operation_testing", "exception_handling"],

            # 数组索引相关映射
            "array_index_bounds": ["array_operation_testing", "boundary_testing"],
            "array_index_error": ["array_operation_testing", "exception_handling"],
            
            # Resource management patterns
            "resource_management": ["resource_lifecycle_testing", "exception_resource_testing"],
            "resource_leak": ["resource_lifecycle_testing", "exception_resource_testing"],
            "use_after_close": ["resource_lifecycle_testing"],
            
            # Data operation patterns
            "data_operation": ["type_conversion_testing", "arithmetic_edge_testing"],
            "integer_truncation": ["type_conversion_testing"],
            "precision_loss": ["type_conversion_testing"],
            "integer_division": ["arithmetic_edge_testing"],
            "signed_unsigned_comparison": ["type_conversion_testing", "boundary_testing"],
            
            # Concurrency patterns
            "concurrency": ["concurrency_testing"],
            "unsynchronized_shared_state": ["concurrency_testing"],
            "potential_deadlock": ["concurrency_testing"],
            
            # Exception handling patterns
            "exception_handling": ["exception_handling", "error_propagation_testing"],
            "empty_catch": ["error_propagation_testing"],
            "swallowed_exception": ["error_propagation_testing"],
            
            # Validation patterns
            "validation": ["data_validation", "null_empty_testing"],
            "missing_null_check": ["null_empty_testing"],
            "missing_empty_check": ["null_empty_testing"],
            
            # Security patterns
            "security": ["security_testing"],
            "sql_injection": ["security_testing"],
            "hardcoded_credential": ["security_testing"]
        }
        
        # Apply pattern mappings
        for pattern in self.failures:
            pattern_type = pattern.get("type", "unknown")
            pattern_subtype = pattern.get("subtype", "unknown")
            
            # Try to match by specific subtype first
            if pattern_subtype in pattern_mappings:
                for strategy_id in pattern_mappings[pattern_subtype]:
                    self.pattern_to_strategies[pattern_subtype].append(strategy_id)
            
            # Then match by general type
            if pattern_type in pattern_mappings:
                for strategy_id in pattern_mappings[pattern_type]:
                    self.pattern_to_strategies[pattern_type].append(strategy_id)
    
    def _map_conditions_to_strategies(self):
        """Map logic model conditions to relevant strategies"""
        if not self.f_model or not hasattr(self.f_model, 'boundary_conditions'):
            return
            
        # Map boundary conditions
        for condition in self.f_model.boundary_conditions:
            condition_id = f"{condition['method']}_{condition['line']}"
            condition_type = condition.get("type", "")
            
            # Map based on condition type
            if condition_type in ["if_condition"]:
                self.condition_to_strategies[condition_id].append("boundary_testing")
                self.condition_to_strategies[condition_id].append("expression")
                
            elif condition_type in ["while_loop", "for_loop"]:
                self.condition_to_strategies[condition_id].append("boundary_testing")
    
    # def select_strategies(self, state=None, covered_patterns=None, covered_conditions=None, max_strategies=3):
    #     """
    #     Select strategies suitable for the current state with improved exploration for uncovered patterns
        
    #     Parameters:
    #     state (FATestState): Current test state
    #     covered_patterns (set): Covered patterns 
    #     covered_conditions (set): Covered conditions
    #     max_strategies (int): Maximum number of strategies to return
        
    #     Returns:
    #     list: Selected strategies as dictionaries
    #     """
    #     # Track uncovered items
    #     uncovered_patterns = set()
    #     uncovered_pattern_instances = []  # Track specific pattern instances, not just types
    #     uncovered_conditions = set()
        
    #     # Get uncovered patterns
    #     if covered_patterns is not None and self.failures:
    #         for pattern in self.failures:
    #             pattern_id = f"{pattern['type']}_{pattern['location']}"
    #             if pattern_id not in covered_patterns:
    #                 uncovered_patterns.add(pattern['type'])  # Add pattern type
    #                 # Also track specific pattern instances
    #                 uncovered_pattern_instances.append({
    #                     "id": pattern_id,
    #                     "type": pattern['type'],
    #                     "location": pattern['location'],
    #                     "risk_level": pattern.get("risk_level", "medium")
    #                 })
        
    #     # Get uncovered conditions
    #     if covered_conditions is not None and self.f_model and hasattr(self.f_model, 'boundary_conditions'):
    #         for condition in self.f_model.boundary_conditions:
    #             condition_id = f"{condition['method']}_{condition['line']}"
    #             if condition_id not in covered_conditions:
    #                 uncovered_conditions.add(condition_id)
        
    #     # Calculate weights for each strategy
    #     strategy_weights = {}
    #     forced_strategies = []
        
    #     # NEW: Track strategies that haven't been used for several iterations
    #     if not hasattr(self, 'strategy_last_used'):
    #         self.strategy_last_used = {sid: 0 for sid in self.strategies}
        
    #     # NEW: Sort uncovered patterns by risk level
    #     high_risk_uncovered = [p for p in uncovered_pattern_instances if p["risk_level"] == "high"]
        
    #     # NEW: Add iteration counter if not present
    #     if not hasattr(self, 'current_iteration'):
    #         self.current_iteration = 0
    #     self.current_iteration += 1
        
    #     # Check for strategies that haven't been used recently
    #     stagnant_strategies = []
    #     for sid, last_used in self.strategy_last_used.items():
    #         if self.current_iteration - last_used > 5:  # Not used for 5+ iterations
    #             stagnant_strategies.append(sid)
        
    #     # NEW: Force high-risk pattern targeting
    #     if high_risk_uncovered:
    #         # Prioritize strategies for high-risk patterns
    #         for pattern in high_risk_uncovered:
    #             pattern_type = pattern["type"]
    #             if pattern_type in self.pattern_to_strategies:
    #                 # Get strategies for this pattern type and force at least one
    #                 strategies_for_pattern = self.pattern_to_strategies[pattern_type]
    #                 if strategies_for_pattern:
    #                     forced_strategy = random.choice(strategies_for_pattern)
    #                     if forced_strategy not in forced_strategies:
    #                         forced_strategies.append(forced_strategy)
    #                         logger.info(f"Forcing strategy {forced_strategy} for high-risk pattern {pattern['id']}")
        
    #     # Add strategies that haven't been used for a while
    #     if stagnant_strategies and len(forced_strategies) < 2:
    #         # Add some stagnant strategies
    #         strategies_to_add = random.sample(stagnant_strategies, min(2 - len(forced_strategies), len(stagnant_strategies)))
    #         for strategy_id in strategies_to_add:
    #             if strategy_id not in forced_strategies:
    #                 forced_strategies.append(strategy_id)
    #                 logger.info(f"Adding stagnant strategy {strategy_id} to forced list")
        
    #     # NEW: Add strategy targeting specific pattern instances
    #     if uncovered_pattern_instances and not forced_strategies:
    #         # Select a specific uncovered pattern instance
    #         pattern = random.choice(uncovered_pattern_instances)
    #         pattern_type = pattern["type"]
            
    #         if pattern_type in self.pattern_to_strategies:
    #             strategies = self.pattern_to_strategies[pattern_type]
    #             if strategies:
    #                 strategy_id = random.choice(strategies)
    #                 forced_strategies.append(strategy_id)
    #                 logger.info(f"Forcing strategy {strategy_id} for uncovered pattern {pattern['id']}")
        
    #     # Calculate weights with updated factors
    #     for strategy_id, strategy in self.strategies.items():
    #         base_weight = strategy.weight
    #         priority_bonus = 0.0
            
    #         # Force using certain strategies
    #         if strategy_id in forced_strategies:
    #             priority_bonus += 3.0  # Significantly increase weight
            
    #         # Boost strategies for uncovered patterns
    #         pattern_bonus = 0.0
    #         strategy_targets_uncovered = False
            
    #         # More specific pattern targeting
    #         for pattern in uncovered_pattern_instances:
    #             pattern_type = pattern["type"]
    #             if strategy_id in self.pattern_to_strategies.get(pattern_type, []):
    #                 pattern_bonus += 0.2
    #                 strategy_targets_uncovered = True
                    
    #                 # Extra bonus for high-risk patterns
    #                 if pattern["risk_level"] == "high":
    #                     pattern_bonus += 0.3
            
    #         priority_bonus += min(1.5, pattern_bonus)  # Increased cap to 1.5
            
    #         # Boost for targeting uncovered conditions
    #         condition_bonus = 0.0
    #         for condition_id in uncovered_conditions:
    #             if strategy_id in self.condition_to_strategies.get(condition_id, []):
    #                 condition_bonus += 0.1
    #                 strategy_targets_uncovered = True
            
    #         priority_bonus += min(0.8, condition_bonus)  # Increased cap
            
    #         # NEW: Bonus for stagnant strategies that target uncovered items
    #         if strategy_id in stagnant_strategies and strategy_targets_uncovered:
    #             priority_bonus += 1.0
            
    #         # Adjust usage penalty - more aggressive
    #         usage_count = self.strategy_usage.get(strategy_id, 0)
            
    #         # NEW: Logarithmic penalty that grows more slowly with usage
    #         import math
    #         if usage_count > 0:
    #             usage_penalty = 0.3 * math.log(usage_count + 1)
    #         else:
    #             usage_penalty = 0
                
    #         # NEW: Add exploration factor that increases over time for stagnant coverage
    #         if hasattr(state, 'stagnant_coverage_iterations') and state.stagnant_coverage_iterations > 5:
    #             exploration_factor = 0.5 + (random.random() * 0.5)  # Between 0.5 and 1.0
    #         else:
    #             exploration_factor = random.random() * 0.3  # Original factor
            
    #         # Final weight calculation
    #         strategy_weights[strategy_id] = max(0.1, base_weight + priority_bonus + exploration_factor - usage_penalty)
        
    #     # The rest of the method remains similar but with better logging and tracking
        
    #     # Select strategies based on weights (weighted random sampling)
    #     selected_strategies = []
    #     available_ids = list(strategy_weights.keys())
        
    #     # First add forced strategies
    #     for strategy_id in forced_strategies:
    #         if strategy_id in self.strategies and strategy_id in available_ids:
    #             selected_strategies.append(self.strategies[strategy_id].to_dict())
    #             available_ids.remove(strategy_id)
    #             self.strategy_usage[strategy_id] = self.strategy_usage.get(strategy_id, 0) + 1
    #             # Update last used iteration
    #             self.strategy_last_used[strategy_id] = self.current_iteration
        
    #     # Continue selecting strategies until we have enough
    #     while len(selected_strategies) < max_strategies and available_ids:
    #         weights = [strategy_weights[sid] for sid in available_ids]
    #         total_weight = sum(weights)
            
    #         if total_weight <= 0:
    #             break
                
    #         # Convert to probabilities
    #         probabilities = [w / total_weight for w in weights]
            
    #         # Select strategy
    #         selected_idx = random.choices(range(len(available_ids)), probabilities)[0]
    #         selected_id = available_ids[selected_idx]
            
    #         # Add to selected strategies
    #         selected_strategy = self.strategies[selected_id]
    #         selected_strategies.append(selected_strategy.to_dict())
            
    #         # Record usage
    #         self.strategy_usage[selected_id] = self.strategy_usage.get(selected_id, 0) + 1
    #         # Update last used iteration
    #         self.strategy_last_used[selected_id] = self.current_iteration
            
    #         # Remove from available IDs to avoid duplicates
    #         available_ids.pop(selected_idx)
        
    #     # Make sure we have at least one strategy
    #     if not selected_strategies:
    #         # Default to boundary_testing or expression
    #         if "boundary_testing" in self.strategies:
    #             selected_strategies.append(self.strategies["boundary_testing"].to_dict())
    #             self.strategy_usage["boundary_testing"] = self.strategy_usage.get("boundary_testing", 0) + 1
    #             self.strategy_last_used["boundary_testing"] = self.current_iteration
    #         elif "expression" in self.strategies:
    #             selected_strategies.append(self.strategies["expression"].to_dict())
    #             self.strategy_usage["expression"] = self.strategy_usage.get("expression", 0) + 1
    #             self.strategy_last_used["expression"] = self.current_iteration
    #         else:
    #             # Fall back to any available strategy
    #             strategy_id = next(iter(self.strategies))
    #             selected_strategies.append(self.strategies[strategy_id].to_dict())
    #             self.strategy_usage[strategy_id] = self.strategy_usage.get(strategy_id, 0) + 1
    #             self.strategy_last_used[strategy_id] = self.current_iteration
        
    #     # Ensure random exploration occasionally appears
    #     if len(selected_strategies) < max_strategies:
    #         if "random_exploration" in self.strategies and random.random() < 0.3:
    #             selected_strategies.append(self.strategies["random_exploration"].to_dict())
    #             self.strategy_usage["random_exploration"] = self.strategy_usage.get("random_exploration", 0) + 1
    #             self.strategy_last_used["random_exploration"] = self.current_iteration
        
    #     # Log strategy selection information
    #     selected_ids = [s.get("id", "unknown") for s in selected_strategies]
    #     logger.info(f"Selected strategies: {selected_ids}")
    #     logger.info(f"Uncovered patterns: {len(uncovered_pattern_instances)}, High-risk uncovered: {len(high_risk_uncovered)}")
        
    #     return selected_strategies

    def select_strategies(self, state, covered_patterns=None, covered_conditions=None, business_logic_issues=None):
        """
        Select test strategies based on current state and business logic analysis
        
        Parameters:
        state (FATestState): Current test state
        covered_patterns (set): Already covered patterns
        covered_conditions (set): Already covered branch conditions
        business_logic_issues (list): Potential business logic issues identified by analyzer
        
        Returns:
        list: Selected strategies with weights
        """
        # Initialize base strategies with default weights
        all_strategies = {
            "boundary_testing": {"name": "Boundary Value Testing", "weight": 1.0},
            "expression": {"name": "Logical Expression Testing", "weight": 1.0},
            "exception_handling": {"name": "Exception Path Testing", "weight": 0.7},
            "data_validation": {"name": "Data Validation Testing", "weight": 0.6},
            "resource_management": {"name": "Resource Management Testing", "weight": 0.5},
            "state_transition": {"name": "State Transition Testing", "weight": 0.8},
            "business_logic": {"name": "Business Logic Testing", "weight": 0.0}  # New strategy
        }
        
        # Boost strategies based on covered patterns
        if covered_patterns:
            # Adjust weights based on pattern coverage
            uncovered_patterns = self.failures if self.failures else []
            if isinstance(uncovered_patterns, list) and covered_patterns:
                uncovered_patterns = [p for p in uncovered_patterns 
                                if f"{p['type']}_{p['location']}" not in covered_patterns]
            
            # Boost relevant strategies based on uncovered pattern types
            for pattern in uncovered_patterns:
                pattern_type = pattern.get("type", "")
                
                if "boundary" in pattern_type or "off_by_one" in pattern_type:
                    all_strategies["boundary_testing"]["weight"] += 0.2
                elif "boolean_bug" in pattern_type or "operator" in pattern_type:
                    all_strategies["expression"]["weight"] += 0.2
                elif "null" in pattern_type or "exception" in pattern_type:
                    all_strategies["exception_handling"]["weight"] += 0.2
                elif "resource" in pattern_type or "leak" in pattern_type:
                    all_strategies["resource_management"]["weight"] += 0.2
                elif "state" in pattern_type:
                    all_strategies["state_transition"]["weight"] += 0.2
        
        # Adjust weights based on covered branch conditions
        if covered_conditions and self.f_model and hasattr(self.f_model, 'boundary_conditions'):
            # Count uncovered condition types
            uncovered_if = 0
            uncovered_loops = 0

            for cond in self.f_model.boundary_conditions:
                # Must match the "{method}_{line}" format used by
                # test_state.track_branch_condition_coverage
                condition_id = f"{cond.get('method', '')}_{cond.get('line', 0)}"
                cond_type = cond.get("type", "")
                
                if condition_id not in covered_conditions:
                    if cond_type == "if_condition":
                        uncovered_if += 1
                    elif cond_type in ["while_loop", "for_loop"]:
                        uncovered_loops += 1
            
            # Adjust weights based on uncovered condition types
            if uncovered_if > uncovered_loops:
                all_strategies["expression"]["weight"] += 0.3
            else:
                all_strategies["boundary_testing"]["weight"] += 0.3
        
        # NEW: Adjust weights based on business logic issues
        if business_logic_issues:
            for issue in business_logic_issues:
                issue_type = issue.get('type', '')
                confidence = issue.get('confidence', 0)
                
                # Significantly boost the business logic strategy
                all_strategies["business_logic"]["weight"] += 0.8 * confidence
                
                # Also boost related specific strategies
                if 'boundary' in issue_type.lower() or 'index' in issue_type.lower():
                    all_strategies["boundary_testing"]["weight"] += 0.5 * confidence
                elif 'logic' in issue_type.lower() or 'condition' in issue_type.lower():
                    all_strategies["expression"]["weight"] += 0.6 * confidence
                elif 'null' in issue_type.lower() or 'exception' in issue_type.lower():
                    all_strategies["exception_handling"]["weight"] += 0.5 * confidence
                elif 'validation' in issue_type.lower() or 'input' in issue_type.lower():
                    all_strategies["data_validation"]["weight"] += 0.5 * confidence
                elif 'state' in issue_type.lower() or 'transition' in issue_type.lower():
                    all_strategies["state_transition"]["weight"] += 0.5 * confidence
                elif 'resource' in issue_type.lower() or 'leak' in issue_type.lower():
                    all_strategies["resource_management"]["weight"] += 0.5 * confidence
        
        # Boost strategies based on current coverage (if we're stuck)
        if state and hasattr(state, "coverage"):
            # If coverage is stagnant, prioritize previously successful strategies
            if hasattr(state, "metadata") and "parent_coverage" in state.metadata:
                parent_coverage = state.metadata["parent_coverage"]
                current_coverage = state.coverage
                
                if abs(current_coverage - parent_coverage) < 0.1:  # Coverage not improving
                    # Boost business logic and state testing to break out of local maximum
                    all_strategies["business_logic"]["weight"] += 0.4
                    all_strategies["state_transition"]["weight"] += 0.3
                    
                    # If we have found bugs already, continue focusing on bug finding
                    if state.detected_bugs:
                        bug_types = set(bug.get("type", "") for bug in state.detected_bugs)
                        
                        for bug_type in bug_types:
                            if "boundary" in bug_type.lower():
                                all_strategies["boundary_testing"]["weight"] += 0.3
                            elif "logic" in bug_type.lower():
                                all_strategies["expression"]["weight"] += 0.3
                            elif "resource" in bug_type.lower():
                                all_strategies["resource_management"]["weight"] += 0.3
        
        # Convert to list format and normalize weights
        strategies = []
        total_weight = sum(strategy["weight"] for strategy in all_strategies.values())
        
        for strategy_id, strategy in all_strategies.items():
            normalized_weight = strategy["weight"] / total_weight if total_weight > 0 else 0
            strategies.append({
                "id": strategy_id,
                "name": strategy["name"],
                "weight": normalized_weight
            })
        
        # Sort by weight (descending)
        strategies.sort(key=lambda x: x["weight"], reverse=True)
        
        # Log selected strategies
        logger.debug(f"Selected strategies: {[(s['id'], s['weight']) for s in strategies[:3]]}")
        
        return strategies

    def record_strategy_result(self, strategy_id, bugs_found=0, coverage_gain=0.0, effective_pattern=None, effective_condition=None):
        """
        Record results of using a strategy
        
        Parameters:
        strategy_id (str): Strategy identifier
        bugs_found (int): Number of bugs found
        coverage_gain (float): Coverage gained
        effective_pattern (str): Pattern ID the strategy was effective for
        effective_condition (str): Condition ID the strategy was effective for
        """
        if strategy_id not in self.strategies:
            logger.warning(f"record_strategy_result called with unknown strategy id '{strategy_id}'; result not recorded")
            return

        # Update strategy metrics
        strategy = self.strategies[strategy_id]
        strategy.record_usage(bugs_found, coverage_gain)
        
        # Update effectiveness metrics
        self.strategy_effectiveness[strategy_id]["bugs"] += bugs_found
        self.strategy_effectiveness[strategy_id]["coverage"] += coverage_gain
        
        # Record effective patterns and conditions
        if effective_pattern:
            strategy.add_effective_pattern(effective_pattern)
            
        if effective_condition:
            strategy.add_effective_condition(effective_condition)
    
    def get_strategy_statistics(self):
        """
        Get statistics about strategy usage and effectiveness
        
        Returns:
        dict: Strategy statistics
        """
        stats = {
            "strategies": {strategy_id: strategy.to_dict() for strategy_id, strategy in self.strategies.items()},
            "usage": dict(self.strategy_usage),
            "effectiveness": dict(self.strategy_effectiveness)
        }
        
        return stats

# Additional test generation strategy classes

class OperatorPrecedenceStrategy:
    """Strategy for testing operator precedence issues"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for operator precedence issues
        
        Parameters:
        pattern (dict): Pattern information
        source_code (str): Source code
        
        Returns:
        list: Test cases
        """
        # Extract the expression from the pattern
        expr = pattern.get("code", "")
        line = pattern.get("location", 0)
        
        # Generate test cases
        test_cases = []
        
        # 1. Test with explicit parentheses
        test_cases.append({
            "type": "parentheses_test",
            "description": "Test with explicit parentheses",
            "line": line,
            "setup": f"// Testing expression: {expr}\n// With explicit parentheses for clarity",
            "test_technique": "Add explicit parentheses to ensure correct operator precedence"
        })
        
        # 2. Test with individual parts of the expression
        if "&&" in expr or "||" in expr:
            test_cases.append({
                "type": "decomposed_test",
                "description": "Test with decomposed boolean expression",
                "line": line,
                "setup": f"// Testing parts of expression: {expr}\n// Breaking down complex condition",
                "test_technique": "Test each part of the boolean expression separately before combining"
            })
        
        # 3. Test for common precedence mistakes
        if "&&" in expr and "||" in expr:
            test_cases.append({
                "type": "precedence_confusion_test",
                "description": "Test for AND/OR precedence confusion",
                "line": line,
                "setup": f"// Testing for AND/OR precedence in: {expr}\n// && has higher precedence than ||",
                "test_technique": "Test cases that would give different results depending on operator precedence"
            })
            
        return test_cases

class BoundaryTestStrategy:
    """Strategy for testing boundary conditions"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for boundary conditions
        
        Parameters:
        pattern (dict): Pattern information
        source_code (str): Source code
        
        Returns:
        list: Test cases
        """
        line = pattern.get("location", 0)
        condition = pattern.get("code", "")
        
        test_cases = []
        
        # 1. Test at boundary
        test_cases.append({
            "type": "at_boundary_test",
            "description": "Test at exact boundary value",
            "line": line,
            "setup": f"// Testing condition: {condition}\n// Exactly at boundary",
            "test_technique": "Test with value exactly at the boundary condition"
        })
        
        # 2. Test just below boundary
        test_cases.append({
            "type": "below_boundary_test",
            "description": "Test just below boundary value",
            "line": line,
            "setup": f"// Testing condition: {condition}\n// Just below boundary",
            "test_technique": "Test with value just below the boundary (boundary-1 for integers)"
        })
        
        # 3. Test just above boundary
        test_cases.append({
            "type": "above_boundary_test",
            "description": "Test just above boundary value",
            "line": line,
            "setup": f"// Testing condition: {condition}\n// Just above boundary",
            "test_technique": "Test with value just above the boundary (boundary+1 for integers)"
        })
        
        # 4. Test off-by-one scenarios
        if "<" in condition or ">" in condition:
            test_cases.append({
                "type": "off_by_one_test",
                "description": "Test for off-by-one errors",
                "line": line,
                "setup": f"// Testing condition: {condition}\n// Checking for off-by-one errors",
                "test_technique": "Test both < and <= variants (or > and >=) to check for off-by-one errors"
            })
        
        return test_cases

class BooleanLogicTestStrategy:
    """Strategy for testing boolean logic bugs"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for boolean logic issues
        
        Parameters:
        pattern (dict): Pattern information
        source_code (str): Source code
        
        Returns:
        list: Test cases
        """
        line = pattern.get("location", 0)
        condition = pattern.get("code", "")
        
        test_cases = []
        
        # 1. Test true/false combinations
        test_cases.append({
            "type": "true_false_combinations",
            "description": "Test all true/false combinations",
            "line": line,
            "setup": f"// Testing boolean logic: {condition}\n// With all true/false combinations",
            "test_technique": "Test every combination of true/false values for each variable in the condition"
        })
        
        # 2. Test negation issues
        if "!" in condition:
            test_cases.append({
                "type": "negation_test",
                "description": "Test negation logic",
                "line": line,
                "setup": f"// Testing negation in: {condition}\n// Verification of De Morgan's laws",
                "test_technique": "Test negated expression against equivalent form using De Morgan's laws"
            })
        
        # 3. Test short-circuit behavior
        if "&&" in condition:
            test_cases.append({
                "type": "short_circuit_test",
                "description": "Test short-circuit behavior with AND",
                "line": line,
                "setup": f"// Testing short-circuit: {condition}\n// Second condition shouldn't evaluate if first is false",
                "test_technique": "Test short-circuit behavior by using side-effects in second condition"
            })
            
        if "||" in condition:
            test_cases.append({
                "type": "short_circuit_test",
                "description": "Test short-circuit behavior with OR",
                "line": line,
                "setup": f"// Testing short-circuit: {condition}\n// Second condition shouldn't evaluate if first is true",
                "test_technique": "Test short-circuit behavior by using side-effects in second condition"
            })
        
        return test_cases

# Add new strategy implementations

class ResourceLifecycleTestStrategy:
    """Strategy for generating tests that target resource lifecycle issues"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for resource lifecycle issues
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        str: Test code
        """
        pattern_type = pattern.get("subtype", pattern.get("type"))
        location = pattern.get("location", 0)
        code = pattern.get("code", "")
        
        test_prompt = f"""
        Generate a test that verifies proper resource management for the following code:
        
        ```java
        {code}
        ```
        
        This test should:
        1. Create the resource
        2. Verify it can be used properly
        3. Check that it's correctly released
        4. Verify it cannot be used after release
        
        If this is a potential resource leak, also test what happens in exceptional cases
        to ensure resources are properly closed even when exceptions occur.
        """
        
        return test_prompt

class ExceptionResourceTestStrategy:
    """Strategy for generating tests that verify resources are released during exceptions"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for exception-safe resource handling
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        str: Test code
        """
        pattern_type = pattern.get("subtype", pattern.get("type"))
        location = pattern.get("location", 0)
        code = pattern.get("code", "")
        
        test_prompt = f"""
        Generate a test that verifies resources are properly released during exceptions for:
        
        ```java
        {code}
        ```
        
        This test should:
        1. Set up a scenario where an exception will occur during resource use
        2. Verify that resources are properly closed/released even when exceptions happen
        3. Use try-catch blocks to verify the exception is thrown correctly
        4. Check for resource leaks after the operation
        """
        
        return test_prompt

class TypeConversionTestStrategy:
    """Strategy for generating tests that target type conversion issues"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for type conversion issues
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        str: Test code
        """
        pattern_type = pattern.get("subtype", pattern.get("type"))
        location = pattern.get("location", 0)
        code = pattern.get("code", "")
        
        test_prompt = f"""
        Generate a test that verifies the correctness of the following type conversion:
        
        ```java
        {code}
        ```
        
        This test should:
        1. Test the conversion with values at the boundaries of the source type
        2. Verify that precision loss or truncation is handled as expected
        3. Test with special values (very large, very small, negative) if applicable
        4. Assert expected results match actual results after conversion
        """
        
        return test_prompt

class ArithmeticEdgeTestStrategy:
    """Strategy for generating tests that target arithmetic edge cases"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for arithmetic edge cases
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        str: Test code
        """
        pattern_type = pattern.get("subtype", pattern.get("type"))
        location = pattern.get("location", 0)
        code = pattern.get("code", "")
        
        test_prompt = f"""
        Generate a test that verifies the correctness of the following arithmetic operation:
        
        ```java
        {code}
        ```
        
        This test should:
        1. Test the operation with extreme values (MIN_VALUE, MAX_VALUE for the type)
        2. Test division with zero or values close to zero if applicable
        3. Test with large values that might cause overflow
        4. Verify that the operation handles edge cases correctly
        """
        
        return test_prompt

class ErrorPropagationTestStrategy:
    """Strategy for generating tests that verify proper error propagation"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for error propagation issues
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        str: Test code
        """
        pattern_type = pattern.get("subtype", pattern.get("type"))
        location = pattern.get("location", 0)
        code = pattern.get("code", "")
        
        test_prompt = f"""
        Generate a test that verifies proper error handling for:
        
        ```java
        {code}
        ```
        
        This test should:
        1. Force an exception to occur in the code
        2. Verify that the exception is properly caught and/or propagated
        3. Check that error information is preserved
        4. Ensure the system is left in a consistent state after the error
        
        If this is an empty catch block or exception swallowing issue, verify that
        important exceptions aren't silently ignored.
        """
        
        return test_prompt
    
class StringOperationTestStrategy:
    """Strategy for generating tests that target string operation issues"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for string operation issues
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        list: Test cases
        """
        line = pattern.get("location", 0)
        code = pattern.get("code", "")
        pattern_type = pattern.get("type", "")
        
        test_cases = []
        
        # 1. Test with empty string
        test_cases.append({
            "type": "empty_string_test",
            "description": "Test with empty string",
            "line": line,
            "setup": f"// Testing with empty string: {code}\n// Empty strings can cause index errors",
            "test_technique": "Test operation with empty string to check for proper length validation"
        })
        
        # 2. Test with index at string length
        test_cases.append({
            "type": "length_boundary_test",
            "description": "Test with index at string length",
            "line": line,
            "setup": f"// Testing with index at boundary: {code}\n// String operations should validate against length",
            "test_technique": "Test with index equal to string length (should cause exception)"
        })
        
        # 3. Test with negative index
        test_cases.append({
            "type": "negative_index_test",
            "description": "Test with negative index",
            "line": line,
            "setup": f"// Testing with negative index: {code}\n// Negative indices should be rejected",
            "test_technique": "Test with negative index to verify index validation"
        })
        
        # 4. Test with index at string length - 1 (last valid position)
        test_cases.append({
            "type": "last_char_test",
            "description": "Test with index at last valid position",
            "line": line,
            "setup": f"// Testing with last valid index: {code}\n// Last character access should work correctly",
            "test_technique": "Test with index at last valid position (length-1) to verify edge case handling"
        })
        
        # 5. Test substring operations with various index combinations
        if "substring" in code:
            test_cases.append({
                "type": "substring_edge_test",
                "description": "Test substring with edge indices",
                "line": line,
                "setup": f"// Testing substring with edge indices: {code}\n// Various combinations of start/end",
                "test_technique": "Test substring with start=length, start>end, negative indices, etc."
            })
            
        return test_cases

class ArrayOperationTestStrategy:
    """Strategy for generating tests that target array operation issues"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for array operation issues
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        list: Test cases
        """
        line = pattern.get("location", 0)
        code = pattern.get("code", "")
        pattern_type = pattern.get("subtype", pattern.get("type", ""))
        
        test_cases = []
        
        # 1. Test with empty array
        test_cases.append({
            "type": "empty_array_test",
            "description": "Test with empty array",
            "line": line,
            "setup": f"// Testing with empty array: {code}\n// Empty arrays require special handling",
            "test_technique": "Test operation with empty array (length 0) to verify proper handling"
        })
        
        # 2. Test with index at array length
        test_cases.append({
            "type": "length_boundary_test",
            "description": "Test with index at array length",
            "line": line,
            "setup": f"// Testing with index at boundary: {code}\n// Array operations should validate against length",
            "test_technique": "Test with index equal to array length (should cause exception)"
        })
        
        # 3. Test with negative index
        test_cases.append({
            "type": "negative_index_test",
            "description": "Test with negative index",
            "line": line,
            "setup": f"// Testing with negative index: {code}\n// Negative indices should be rejected",
            "test_technique": "Test with negative index to verify index validation"
        })
        
        # 4. Test with index at array length - 1 (last valid position)
        test_cases.append({
            "type": "last_element_test",
            "description": "Test with index at last valid position",
            "line": line,
            "setup": f"// Testing with last valid index: {code}\n// Last element access should work correctly",
            "test_technique": "Test with index at last valid position (length-1) to verify edge case handling"
        })
        
        # 5. Test with loop iteration pattern if relevant
        if "loop" in pattern_type or "for" in code or "while" in code:
            test_cases.append({
                "type": "loop_iteration_test",
                "description": "Test array access in loop iterations",
                "line": line,
                "setup": f"// Testing loop iteration: {code}\n// Verify loop boundary conditions",
                "test_technique": "Test loop with various bounds: < length, <= length-1, etc."
            })
            
        # 6. Test multidimensional array if relevant
        if pattern_type == "multidimensional" or "][][" in code:
            test_cases.append({
                "type": "multidim_array_test",
                "description": "Test multidimensional array access",
                "line": line,
                "setup": f"// Testing multidimensional array: {code}\n// Both dimensions need validation",
                "test_technique": "Test with various combinations of indices for both dimensions"
            })
            
        # 7. Test off-by-one scenarios
        if "off_by_one" in pattern_type or "<=" in code:
            test_cases.append({
                "type": "off_by_one_test",
                "description": "Test for off-by-one errors",
                "line": line,
                "setup": f"// Testing for off-by-one error: {code}\n// Verify correct boundary behavior",
                "test_technique": "Test boundary conditions with careful attention to off-by-one errors"
            })
            
        # 8. Test concurrent modification if it looks like an array iteration
        if "for" in code and "[]" in code:
            test_cases.append({
                "type": "concurrent_modification_test",
                "description": "Test for array modification during iteration",
                "line": line,
                "setup": f"// Testing array modification: {code}\n// Verify behavior when array is modified during iteration",
                "test_technique": "Test behavior when array is modified while being iterated over"
            })
            
        return test_cases

class NullEmptyTestStrategy:
    """Strategy for generating tests that verify handling of null and empty inputs"""
    
    @staticmethod
    def generate_tests(pattern, source_code):
        """
        Generate tests for null and empty input handling
        
        Parameters:
        pattern (dict): Detected pattern
        source_code (str): Source code
        
        Returns:
        str: Test code
        """
        pattern_type = pattern.get("subtype", pattern.get("type"))
        location = pattern.get("location", 0)
        code = pattern.get("code", "")
        
        test_prompt = f"""
        Generate a test that verifies proper handling of null or empty values for:
        
        ```java
        {code}
        ```
        
        This test should:
        1. Test with null inputs where applicable
        2. Test with empty strings, collections, or arrays
        3. Verify that the code correctly validates inputs
        4. Check that appropriate exceptions are thrown for invalid inputs
        """
        
        return test_prompt
