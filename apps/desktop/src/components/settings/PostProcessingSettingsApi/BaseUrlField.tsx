import React, { useState } from "react";
import { Input } from "../../ui/Input";

interface BaseUrlFieldProps {
  value: string;
  label: string;
  onBlur: (value: string) => void;
  disabled: boolean;
  unavailableReasonId?: string;
  placeholder?: string;
  className?: string;
}

export const BaseUrlField: React.FC<BaseUrlFieldProps> = React.memo(
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

    const disabledMessage = disabled
      ? "Base URL is managed by the selected provider."
      : undefined;

    return (
      <Input
        type="text"
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
        onBlur={() => {
          if (!unavailableReasonId) onBlur(localValue);
          setLocalValue(value);
        }}
        placeholder={placeholder}
        variant="compact"
        disabled={disabled && !unavailableReasonId}
        className={`min-w-0 flex-1 ${unavailableReasonId ? "cursor-not-allowed opacity-60" : ""} ${className}`}
        title={disabledMessage}
      />
    );
  },
);

BaseUrlField.displayName = "BaseUrlField";
