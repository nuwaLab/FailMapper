#!/usr/bin/env python3
"""
Semantic Analyzer

This module extracts semantic intent signals from code,
focusing on names, documentation, and types.
"""

import re
import javalang
import logging
from typing import Dict, Any, List

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("semantic_analyzer")

class SemanticAnalyzer:
    """
    Extracts semantic signals from source code to understand intended behavior
    """
    
    def __init__(self):
        """Initialize the semantic analyzer"""
        # Common verbs in method names
        self.action_verbs = {
            "get": "retrieval",
            "set": "modification",
            "is": "boolean_check",
            "has": "boolean_check",
            "can": "capability_check",
            "create": "creation",
            "build": "creation",
            "find": "search",
            "search": "search",
            "compute": "computation",
            "calculate": "computation",
            "validate": "validation",
            "check": "validation",
            "convert": "transformation",
            "transform": "transformation",
            "add": "insertion",
            "insert": "insertion",
            "remove": "deletion",
            "delete": "deletion",
            "update": "modification",
            "sort": "ordering",
            "order": "ordering",
            "filter": "filtering",
            "parse": "parsing",
            "load": "loading",
            "save": "saving",
            "store": "saving",
            "send": "transmission",
            "receive": "reception",
            "handle": "handling",
            "process": "processing",
            "initialize": "initialization"
        }
        
        # Common type semantics
        self.type_semantics = {
            "List": "ordered_collection",
            "Set": "unique_collection",
            "Map": "key_value_collection",
            "boolean": "truth_value",
            "int": "integer_value",
            "long": "large_integer_value",
            "double": "decimal_value",
            "float": "decimal_value",
            "String": "text_value",
            "Date": "temporal_value",
            "void": "no_return_value",
            "Exception": "error_condition",
            "Object": "generic_reference",
            "Optional": "nullable_wrapper"
        }
    
    def extract_semantic_signals(self, source_code: str, class_name: str, method_name: str) -> Dict[str, Any]:
        """
        Extract all semantic signals from the code
        
        Parameters:
        source_code (str): Source code
        class_name (str): Class name
        method_name (str): Method name
        
        Returns:
        dict: Semantic signals
        """
        try:
            # Compile results from various analyzers
            naming_signals = self._analyze_naming_semantics(source_code, class_name, method_name)
            documentation_signals = self._extract_documentation(source_code, method_name)
            type_signals = self._analyze_type_semantics(source_code, method_name)
            context_signals = self._analyze_contextual_usage(source_code, class_name, method_name)
            
            return {
                "naming_signals": naming_signals,
                "documentation_signals": documentation_signals,
                "type_signals": type_signals,
                "context_signals": context_signals
            }
        except Exception as e:
            logger.error(f"Error extracting semantic signals: {str(e)}")
            return {}
    
    def _analyze_naming_semantics(self, source_code: str, class_name: str, method_name: str) -> Dict[str, Any]:
        """
        Analyze method naming semantics
        
        Parameters:
        source_code (str): Source code
        class_name (str): Class name
        method_name (str): Method name
        
        Returns:
        dict: Naming semantic signals
        """
        # Parse method name into tokens
        tokens = self._tokenize_camel_case(method_name)
        
        # Extract parameters
        params = self._extract_method_parameters(source_code, method_name)
        
        # Identify action and object pattern
        action_object = self._identify_action_object_pattern(tokens)
        
        # Categorize method semantics
        category = self._categorize_method_semantics(tokens)
        
        return {
            "method_name": method_name,
            "name_tokens": tokens,
            "parameter_names": [param["name"] for param in params],
            "action_verb": action_object["action"],
            "target_object": action_object["object"],
            "semantic_category": category
        }
    
    def _extract_documentation(self, source_code: str, method_name: str) -> Dict[str, Any]:
        """
        Extract documentation comments for the method
        
        Parameters:
        source_code (str): Source code
        method_name (str): Method name
        
        Returns:
        dict: Documentation signals
        """
        # Try to extract Javadoc for the method
        javadoc_pattern = r'/\*\*(.*?)\*/\s*(?:public|private|protected).*?\s+' + re.escape(method_name) + r'\s*\('
        javadoc_match = re.search(javadoc_pattern, source_code, re.DOTALL)
        
        javadoc = ""
        params_doc = {}
        return_doc = ""
        
        if javadoc_match:
            javadoc = javadoc_match.group(1).strip()
            
            # Extract parameter documentation
            param_matches = re.finditer(r'@param\s+(\w+)\s+([^\n@]*)', javadoc)
            for param_match in param_matches:
                params_doc[param_match.group(1)] = param_match.group(2).strip()
            
            # Extract return documentation
            return_match = re.search(r'@return\s+([^\n@]*)', javadoc)
            if return_match:
                return_doc = return_match.group(1).strip()
        
        return {
            "javadoc": javadoc,
            "parameter_docs": params_doc,
            "return_doc": return_doc,
            "has_documentation": bool(javadoc)
        }
    
    def _analyze_type_semantics(self, source_code: str, method_name: str) -> Dict[str, Any]:
        """
        Analyze type semantics for return type and parameters
        
        Parameters:
        source_code (str): Source code
        method_name (str): Method name
        
        Returns:
        dict: Type semantic signals
        """
        # Extract method signature
        method_pattern = r'(?:public|private|protected)?\s+(?:static\s+)?(?:final\s+)?(\w+(?:<[^>]+>)?)\s+' + re.escape(method_name) + r'\s*\(([^)]*)\)'
        method_match = re.search(method_pattern, source_code)
        
        return_type = ""
        parameter_types = []
        
        if method_match:
            return_type = method_match.group(1).strip()
            
            # Extract parameter types
            if method_match.group(2):
                params = method_match.group(2).split(',')
                for param in params:
                    param = param.strip()
                    if param:
                        param_parts = param.split()
                        if len(param_parts) >= 2:
                            parameter_types.append(param_parts[0])
        
        # Get type semantics
        return_type_semantics = self._get_type_semantics(return_type)
        param_type_semantics = [self._get_type_semantics(param_type) for param_type in parameter_types]
        
        return {
            "return_type": return_type,
            "parameter_types": parameter_types,
            "return_type_semantics": return_type_semantics,
            "parameter_type_semantics": param_type_semantics
        }
    
    def _analyze_contextual_usage(self, source_code: str, class_name: str, method_name: str) -> Dict[str, Any]:
        """
        Analyze contextual usage of the method
        
        Parameters:
        source_code (str): Source code
        class_name (str): Class name
        method_name (str): Method name
        
        Returns:
        dict: Contextual usage signals
        """
        # Try to determine class purpose
        class_purpose = self._determine_class_purpose(source_code, class_name)
        
        # Find method callers
        callers = self._find_method_callers(source_code, method_name)
        
        # Find similar methods in the class
        similar_methods = self._find_similar_methods(source_code, method_name)
        
        return {
            "class_name": class_name,
            "class_purpose": class_purpose,
            "method_callers": callers,
            "similar_methods": similar_methods
        }
    
    def _tokenize_camel_case(self, name: str) -> List[str]:
        """Split camelCase or PascalCase name into tokens"""
        # Handle special case of single character
        if len(name) <= 1:
            return [name.lower()]
            
        # Add space before capitals, then split
        name_with_spaces = re.sub(r'([a-z])([A-Z])', r'\1 \2', name)
        tokens = name_with_spaces.split()
        return [token.lower() for token in tokens]
    
    def _extract_method_parameters(self, source_code: str, method_name: str) -> List[Dict[str, str]]:
        """Extract method parameters"""
        method_pattern = r'(?:public|private|protected)?\s+(?:static\s+)?(?:final\s+)?(?:\w+(?:<[^>]+>)?)\s+' + re.escape(method_name) + r'\s*\(([^)]*)\)'
        method_match = re.search(method_pattern, source_code)
        
        params = []
        if method_match and method_match.group(1):
            param_str = method_match.group(1).strip()
            if param_str:
                param_list = param_str.split(',')
                for param in param_list:
                    param = param.strip()
                    param_parts = param.split()
                    if len(param_parts) >= 2:
                        param_type = param_parts[0]
                        param_name = param_parts[-1]
                        params.append({
                            "type": param_type,
                            "name": param_name
                        })
        
        return params
    
    def _identify_action_object_pattern(self, tokens: List[str]) -> Dict[str, str]:
        """Identify action verb and target object in method name"""
        if not tokens:
            return {"action": "", "object": ""}
            
        action = tokens[0]
        object_parts = tokens[1:] if len(tokens) > 1 else []
        
        # Get action category if it's a known verb
        action_category = self.action_verbs.get(action, action)
        
        # Combine remaining tokens as object
        target_object = ' '.join(object_parts)
        
        return {
            "action": action,
            "action_category": action_category,
            "object": target_object
        }
    
    def _categorize_method_semantics(self, tokens: List[str]) -> str:
        """Categorize method semantics based on tokens"""
        if not tokens:
            return "unknown"
            
        first_token = tokens[0]
        
        # Check for accessor/mutator pattern
        if first_token == "get":
            return "accessor"
        elif first_token == "set":
            return "mutator"
        elif first_token in ["is", "has", "can", "should"]:
            return "predicate"
        elif first_token in ["find", "search", "query", "lookup"]:
            return "finder"
        elif first_token in ["create", "build", "generate", "make"]:
            return "factory"
        elif first_token in ["calculate", "compute", "determine"]:
            return "calculator"
        elif first_token in ["validate", "verify", "check"]:
            return "validator"
        elif first_token in ["convert", "transform", "map"]:
            return "transformer"
        elif first_token in ["add", "insert", "put", "append"]:
            return "adder"
        elif first_token in ["remove", "delete", "clear"]:
            return "remover"
        elif first_token in ["update", "modify", "change"]:
            return "updater"
        elif first_token == "init" or first_token == "initialize":
            return "initializer"
        
        # Default fallback
        return self.action_verbs.get(first_token, "action")
    
    def _get_type_semantics(self, type_name: str) -> str:
        """Get semantic meaning of a type"""
        # Handle generics
        base_type = type_name.split('<')[0] if '<' in type_name else type_name
        
        # Handle arrays
        base_type = base_type.replace("[]", "")
        
        return self.type_semantics.get(base_type, "unknown")
    
    def _determine_class_purpose(self, source_code: str, class_name: str) -> str:
        """Try to determine the purpose of the class"""
        # Check class name for common patterns
        lowercase_name = class_name.lower()
        
        if "controller" in lowercase_name:
            return "controller"
        elif "service" in lowercase_name:
            return "service"
        elif "repository" in lowercase_name or "dao" in lowercase_name:
            return "repository"
        elif "model" in lowercase_name or "entity" in lowercase_name:
            return "model"
        elif "dto" in lowercase_name:
            return "data_transfer"
        elif "util" in lowercase_name:
            return "utility"
        elif "factory" in lowercase_name:
            return "factory"
        elif "builder" in lowercase_name:
            return "builder"
        elif "manager" in lowercase_name:
            return "manager"
        elif "handler" in lowercase_name:
            return "handler"
        elif "adapter" in lowercase_name:
            return "adapter"
        elif "provider" in lowercase_name:
            return "provider"
        
        # Check for common interfaces/base classes
        try:
            extends_pattern = r'class\s+' + re.escape(class_name) + r'\s+extends\s+(\w+)'
            implements_pattern = r'class\s+' + re.escape(class_name) + r'\s+(?:extends\s+\w+\s+)?implements\s+([\w,\s]+)'
            
            extends_match = re.search(extends_pattern, source_code)
            implements_match = re.search(implements_pattern, source_code)
            
            if extends_match:
                parent = extends_match.group(1).lower()
                if "controller" in parent:
                    return "controller"
                elif "service" in parent:
                    return "service"
                # Add more parent class checks as needed
            
            if implements_match:
                interfaces = [intf.strip().lower() for intf in implements_match.group(1).split(',')]
                if any("repository" in intf for intf in interfaces):
                    return "repository"
                # Add more interface checks as needed
                
        except Exception:
            pass
        
        # Default
        return "class"
    
    def _find_method_callers(self, source_code: str, method_name: str) -> List[str]:
        """Find methods that call this method"""
        caller_methods = []
        
        # This is a simplified approach - a real implementation would need proper AST analysis
        calls_pattern = r'(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?(?:\w+(?:<[^>]+>)?)\s+(\w+)\s*\([^)]*\)\s*\{[^}]*' + re.escape(method_name) + r'\s*\('
        calls_matches = re.finditer(calls_pattern, source_code)
        
        for match in calls_matches:
            caller_methods.append(match.group(1))
        
        return caller_methods
    
    def _find_similar_methods(self, source_code: str, method_name: str) -> List[str]:
        """Find methods with similar names or purposes"""
        similar_methods = []
        tokens = self._tokenize_camel_case(method_name)
        
        if not tokens:
            return []
            
        first_token = tokens[0]
        
        # Find methods that start with the same verb or have similar purpose
        method_pattern = r'(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?(?:\w+(?:<[^>]+>)?)\s+(\w+)\s*\('
        method_matches = re.finditer(method_pattern, source_code)
        
        for match in method_matches:
            other_method = match.group(1)
            if other_method != method_name:
                other_tokens = self._tokenize_camel_case(other_method)
                if other_tokens and other_tokens[0] == first_token:
                    similar_methods.append(other_method)
        
        return similar_methods