from typing import Dict, List, Any
import javalang
from java_type_converter import convert_type  # 导入新的类型转换函数

def extract_data_flow_graph(node: javalang.tree.ClassDeclaration) -> Dict[str, List[Dict[str, Any]]]:
    dfg = {}
    for method in node.methods:
        method_dfg = analyze_method_dfg(method)
        dfg[method.name] = method_dfg
    return dfg

def analyze_method_dfg(method: javalang.tree.MethodDeclaration) -> List[Dict[str, Any]]:
    dfg = []
    variables = set()
    
    for param in method.parameters:
        variables.add(param.name)
        dfg.append({
            "from": "parameter",
            "to": param.name,
            "type": "input",
            "details": f"{convert_type(param.type)} {param.name}"
        })
    
    if method.body:
        analyze_block(method.body, dfg, variables)
    
    return dfg

def analyze_block(block, dfg, variables):
    for statement in block:
        analyze_statement(statement, dfg, variables)

def analyze_statement(statement, dfg, variables):
    if statement is None:
        return
    
    if isinstance(statement, javalang.tree.LocalVariableDeclaration):
        for declarator in statement.declarators:
            variables.add(declarator.name)
            var_type = convert_type(statement.type)
            if declarator.initializer:
                dfg.append({
                    "from": extract_variables_from_expression(declarator.initializer),
                    "to": declarator.name,
                    "type": "assignment",
                    "details": f"{var_type} {declarator.name} = {simplify_expression(declarator.initializer)}"
                })
            else:
                dfg.append({
                    "from": [],
                    "to": declarator.name,
                    "type": "declaration",
                    "details": f"{var_type} {declarator.name}"
                })
    elif isinstance(statement, javalang.tree.StatementExpression):
        if statement.expression is None:
            return
        if isinstance(statement.expression, javalang.tree.Assignment):
            lhs = simplify_expression(statement.expression.expressionl)
            rhs = extract_variables_from_expression(statement.expression.value)
            details = simplify_expression(statement.expression)
            # Ensure lhs is not empty before splitting
            if lhs:
                to_value = lhs.split('[')[0]  # 取数组名称
            else:
                to_value = ""
            dfg.append({
                "from": rhs,
                "to": to_value,
                "type": "assignment",
                "details": details
            })
        elif isinstance(statement.expression, javalang.tree.MethodInvocation):
            method_name = statement.expression.member
            args = extract_variables_from_expression(statement.expression)
            dfg.append({
                "from": args,
                "to": method_name,
                "type": "method_call",
                "details": simplify_expression(statement.expression)
            })
    elif isinstance(statement, javalang.tree.IfStatement):
        condition = simplify_condition(statement.condition)
        dfg.append({
            "from": extract_variables_from_expression(statement.condition),
            "to": f"if ({condition})",
            "type": "condition",
            "details": condition
        })
        if statement.then_statement:
            analyze_block([statement.then_statement], dfg, variables)
        if statement.else_statement:
            analyze_block([statement.else_statement], dfg, variables)
    elif isinstance(statement, javalang.tree.ReturnStatement):
        if statement.expression:
            dfg.append({
                "from": extract_variables_from_expression(statement.expression),
                "to": "return",
                "type": "return",
                "details": simplify_expression(statement.expression)
            })
    elif isinstance(statement, javalang.tree.ThrowStatement):
        if statement.expression:
            dfg.append({
                "from": extract_variables_from_expression(statement.expression),
                "to": "throw",
                "type": "throw",
                "details": simplify_expression(statement.expression)
            })

def extract_variables_from_expression(expr) -> List[str]:
    if expr is None:
        return []
    if isinstance(expr, javalang.tree.BinaryOperation):
        return extract_variables_from_expression(expr.operandl) + extract_variables_from_expression(expr.operandr)
    elif isinstance(expr, javalang.tree.MemberReference):
        return [expr.member]
    elif isinstance(expr, (javalang.tree.Literal, javalang.tree.This)):
        return []
    elif isinstance(expr, javalang.tree.MethodInvocation):
        return [expr.member] + [var for arg in expr.arguments for var in extract_variables_from_expression(arg)]
    elif isinstance(expr, javalang.tree.ArraySelector):
        return extract_variables_from_expression(expr.index) + extract_variables_from_expression(expr.target)
    elif isinstance(expr, javalang.tree.Assignment):
        return extract_variables_from_expression(expr.expressionl) + extract_variables_from_expression(expr.value)
    elif isinstance(expr, javalang.tree.ClassCreator):
        return [convert_type(expr.type)] + [var for arg in expr.arguments for var in extract_variables_from_expression(arg)]
    else:
        # Safely convert to string and ensure it's not None
        expr_str = str(expr) if expr is not None else ""
        return [expr_str] if expr_str else []

def simplify_condition(condition):
    if condition is None:
        return ""
    return simplify_expression(condition)

def simplify_expression(expr):
    if expr is None:
        return ""
    if isinstance(expr, javalang.tree.BinaryOperation):
        left = simplify_expression(expr.operandl)
        right = simplify_expression(expr.operandr)
        return f"{left} {expr.operator} {right}"
    elif isinstance(expr, javalang.tree.MemberReference):
        result = expr.member
        if expr.selectors:
            for selector in expr.selectors:
                if isinstance(selector, javalang.tree.ArraySelector):
                    result += f"[{simplify_expression(selector.index)}]"
        if hasattr(expr, 'postfix_operators'):
            result += ''.join(expr.postfix_operators)
        if hasattr(expr, 'prefix_operators'):
            result = ''.join(expr.prefix_operators) + result
        return result
    elif isinstance(expr, javalang.tree.Literal):
        return expr.value
    elif isinstance(expr, javalang.tree.This):
        return "this"
    elif isinstance(expr, javalang.tree.MethodInvocation):
        args = ", ".join(simplify_expression(arg) for arg in expr.arguments)
        return f"{expr.member}({args})"
    elif isinstance(expr, javalang.tree.ClassCreator):
        args = ", ".join(simplify_expression(arg) for arg in expr.arguments)
        return f"new {convert_type(expr.type)}({args})"
    elif isinstance(expr, javalang.tree.ArraySelector):
        return f"{simplify_expression(expr.target)}[{simplify_expression(expr.index)}]"
    elif isinstance(expr, javalang.tree.Assignment):
        left = simplify_expression(expr.expressionl)
        right = simplify_expression(expr.value)
        return f"{left} = {right}"
    else:
        return str(expr) if expr is not None else ""