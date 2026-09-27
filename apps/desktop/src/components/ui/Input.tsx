import React from "react";

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  variant?: "default" | "compact";
}

export const Input: React.FC<InputProps> = ({
  className = "",
  variant = "default",
  disabled,
  ...props
}) => {
  const baseClasses =
    "px-2 py-1 text-sm bg-background border border-mid-gray/30 rounded-lg text-start transition-colors duration-150 placeholder:text-mid-gray/70";

  const interactiveClasses = disabled
    ? "opacity-60 cursor-not-allowed border-mid-gray/20"
    : "hover:border-mid-gray/50 focus:outline-none focus:border-logo-primary focus:ring-2 focus:ring-logo-primary/25";

  const variantClasses = {
    default: "px-3 py-2",
    compact: "px-2 py-1",
  } as const;

  return (
    <input
      className={`${baseClasses} ${variantClasses[variant]} ${interactiveClasses} ${className}`}
      disabled={disabled}
      {...props}
    />
  );
};
