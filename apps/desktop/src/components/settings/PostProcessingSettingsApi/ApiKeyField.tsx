import React, { useState } from "react";
import { Input } from "../../ui/Input";

interface ApiKeyFieldProps {
  value: string;
  label: string;
  onBlur: (value: string) => void;
  disabled: boolean;
  unavailableReasonId?: string;
  placeholder?: string;
  className?: string;
}

export const ApiKeyField: React.FC<ApiKeyFieldProps> = React.memo(
  ({
    value,
    label,
    onBlur,
    disabled,
    unavailableReasonId,
    placeholder,
    className = "",
  }) => {
    const [localValue, setLocalValue] = useState(value);

    // Sync with prop changes
    React.useEffect(() => {
      setLocalValue(value);
    }, [value]);

    return (
      <Input
        type="password"
        aria-label={label}
        aria-disabled={unavailableReasonId ? true : undefined}
        aria-describedby={unavailableReasonId}
        readOnly={!!unavailableReasonId}
        value={localValue}
        onChange={
          unavailableReasonId
            ? undefined
            : (event) => setLocalValue(event.target.value)
        }
        onBlur={unavailableReasonId ? undefined : () => onBlur(localValue)}
        placeholder={placeholder}
        variant="compact"
        disabled={disabled && !unavailableReasonId}
        className={`min-w-0 flex-1 ${unavailableReasonId ? "cursor-not-allowed opacity-60" : ""} ${className}`}
      />
    );
  },
);

ApiKeyField.displayName = "ApiKeyField";
