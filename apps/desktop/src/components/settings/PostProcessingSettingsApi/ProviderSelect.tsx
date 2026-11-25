import React from "react";
import { Dropdown, type DropdownOption } from "../../ui/Dropdown";

interface ProviderSelectProps {
  options: DropdownOption[];
  value: string;
  onChange: (value: string) => void;
  label: string;
  placeholder: string;
  unavailableReasonId?: string;
}

export const ProviderSelect: React.FC<ProviderSelectProps> = React.memo(
  ({ options, value, onChange, label, placeholder, unavailableReasonId }) => {
    return (
      <Dropdown
        options={options}
        selectedValue={value}
        onSelect={onChange}
        label={label}
        placeholder={placeholder}
        unavailableReasonId={unavailableReasonId}
        className="flex-1"
      />
    );
  },
);

ProviderSelect.displayName = "ProviderSelect";
