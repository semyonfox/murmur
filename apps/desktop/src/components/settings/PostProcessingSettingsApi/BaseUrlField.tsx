import React, { useState } from "react";
import { Input } from "../../ui/Input";

interface BaseUrlFieldProps {
  value: string;
  label: string;
  onBlur: (value: string) => void;
  disabled: boolean;
  placeholder?: string;
  className?: string;
}

export const BaseUrlField: React.FC<BaseUrlFieldProps> = React.memo(
  ({ value, label, onBlur, disabled, placeholder, className = "" }) => {
    const [localValue, setLocalValue] = useState(value);

    // Sync with prop changes
    React.useEffect(() => {
      setLocalValue(value);
    }, [value]);

    const disabledMessage = disabled
      ? "Base URL is managed by the selected provider."
      : undefined;

    return (
      <Input
        type="text"
        aria-label={label}
        value={localValue}
        onChange={(event) => setLocalValue(event.target.value)}
        onBlur={() => {
          onBlur(localValue);
          setLocalValue(value);
        }}
        placeholder={placeholder}
        variant="compact"
        disabled={disabled}
        className={`min-w-0 flex-1 ${className}`}
        title={disabledMessage}
      />
    );
  },
);

BaseUrlField.displayName = "BaseUrlField";
