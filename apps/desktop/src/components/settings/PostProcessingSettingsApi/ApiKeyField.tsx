import React, { useState } from "react";
import { Input } from "../../ui/Input";

interface ApiKeyFieldProps {
  value: string;
  label: string;
  onBlur: (value: string) => void;
  disabled: boolean;
  placeholder?: string;
  className?: string;
}

export const ApiKeyField: React.FC<ApiKeyFieldProps> = React.memo(
  ({ value, label, onBlur, disabled, placeholder, className = "" }) => {
    const [localValue, setLocalValue] = useState(value);

    // Sync with prop changes
    React.useEffect(() => {
      setLocalValue(value);
    }, [value]);

    return (
      <Input
        type="password"
        aria-label={label}
        value={localValue}
        onChange={(event) => setLocalValue(event.target.value)}
        onBlur={() => onBlur(localValue)}
        placeholder={placeholder}
        variant="compact"
        disabled={disabled}
        className={`min-w-0 flex-1 ${className}`}
      />
    );
  },
);

ApiKeyField.displayName = "ApiKeyField";
