#!/usr/bin/env python3
import os
import subprocess
import argparse
import getpass
import sys

def extract_project_name(project_path):
    """Extract the project name from the project path"""
    return os.path.basename(project_path)

def get_api_key(args, key_type, env_var_name, arg_attr):
    """Get API key from arguments, environment, or user input"""
    # First check command line argument
    api_key_arg = getattr(args, arg_attr, None)
    if api_key_arg:
        return api_key_arg
    
    # Then check environment variable
    env_key = os.environ.get(env_var_name)
    if env_key:
        print(f"Using {env_var_name} from environment variable")
        return env_key
    
    # Finally ask user for input
    print(f"{env_var_name} not found in arguments or environment.")
    try:
        api_key = getpass.getpass(f"Please enter your {key_type} API key: ")
        if not api_key.strip():
            print("Error: API key cannot be empty")
            sys.exit(1)
        return api_key.strip()
    except KeyboardInterrupt:
        print("\nOperation cancelled by user")
        sys.exit(1)

def setup_api_keys(args):
    """Setup all API keys as environment variables"""
    # ANTHROPIC API key (required)
    anthropic_key = get_api_key(args, "ANTHROPIC", "ANTHROPIC_API_KEY", "anthropic_api_key")
    os.environ['ANTHROPIC_API_KEY'] = anthropic_key
    print("✓ ANTHROPIC API key configured successfully")
    
    # # OpenAI API key (optional)
    # if args.openai_api_key or os.environ.get('OPENAI_API_KEY'):
    #     openai_key = get_api_key(args, "OpenAI", "OPENAI_API_KEY", "openai_api_key")
    #     os.environ['OPENAI_API_KEY'] = openai_key
    #     print("✓ OpenAI API key configured successfully")
    
    # # DeepSeek API key (optional)
    # if args.deepseek_api_key or os.environ.get('DEEPSEEK_API_KEY'):
    #     deepseek_key = get_api_key(args, "DeepSeek", "DEEPSEEK_API_KEY", "deepseek_api_key")
    #     os.environ['DEEPSEEK_API_KEY'] = deepseek_key
    #     print("✓ DeepSeek API key configured successfully")

def main():
    # Set up command line arguments
    parser = argparse.ArgumentParser(description='Run analysis commands in sequence')
    parser.add_argument('project_path', help='Path to the project being tested')
    parser.add_argument('--output_dir', required=True, help='Output directory for results')
    parser.add_argument('--class_name', required=True, help='Name of the class being tested')
    parser.add_argument('--package', required=True, help='Package of the class being tested')
    parser.add_argument('--project_type', choices=['maven', 'gradle'], help='Project type (maven or gradle). If not specified, will auto-detect.')
    parser.add_argument('--anthropic_api_key', help='ANTHROPIC API key (can also be set via ANTHROPIC_API_KEY environment variable)')
    # parser.add_argument('--openai_api_key', help='OpenAI API key (can also be set via OPENAI_API_KEY environment variable)')
    # parser.add_argument('--deepseek_api_key', help='DeepSeek API key (can also be set via DEEPSEEK_API_KEY environment variable)')
    # parser.add_argument('--joda_subdir', default='JodaTime', 
    #                     help='Subdirectory for the project (default: JodaTime)')
    
    args = parser.parse_args()
    
    # Setup API keys
    setup_api_keys(args)
    
    # Extract project name from path
    project_name = extract_project_name(args.project_path)
    
    # Command 1: Run static_analysis.py
    cmd1 = ["python", "static_analysis.py", args.project_path, "--output_dir", args.output_dir]
    print(f"Running command 1: {' '.join(cmd1)}")
    subprocess.run(cmd1, check=True)
    
    # Command 2: Run prompt_generator.py
    json_path = os.path.join(args.output_dir, project_name, f"{project_name}_combined_analysis.json")
    prompts_dir = os.path.join(args.output_dir, project_name, "prompts")
    cmd2 = ["python", "prompt_generator.py", json_path, "--output_dir", prompts_dir]
    print(f"Running command 2: {' '.join(cmd2)}")
    subprocess.run(cmd2, check=True)
    
    # Command 3: Run fa_mcts.py
    cmd3 = ["python", "failmapper.py", 
            "--project", args.project_path, 
            "--prompt", prompts_dir, 
            "--class", args.class_name, 
            "--package", args.package,
           ]
    
    # Add project type if specified
    if args.project_type:
        cmd3.extend(["--project-type", args.project_type])
    
    print(f"Running command 3: {' '.join(cmd3)}")
    subprocess.run(cmd3, check=True)
    
    print("All commands executed successfully!")

if __name__ == "__main__":
    main()