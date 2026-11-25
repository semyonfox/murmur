import React from "react";

type ModelStatus =
  | "ready"
  | "loading"
  | "downloading"
  | "verifying"
  | "extracting"
  | "error"
  | "unloaded"
  | "none";

interface ModelStatusButtonProps {
  status: ModelStatus;
  displayText: string;
  isDropdownOpen?: boolean;
  onClick: () => void;
  className?: string;
}

const ModelStatusButton: React.FC<ModelStatusButtonProps> = ({
  status,
  displayText,
  isDropdownOpen,
  onClick,
  className = "",
}) => {
  const getStatusColor = (status: ModelStatus): string => {
    switch (status) {
      case "ready":
        return "bg-green-400";
      case "loading":
        return "bg-yellow-400";
      case "downloading":
        return "bg-logo-primary";
      case "verifying":
        return "bg-orange-400";
      case "extracting":
        return "bg-orange-400";
      case "error":
        return "bg-red-400";
      case "unloaded":
        return "bg-mid-gray/60";
      case "none":
        return "bg-red-400";
      default:
        return "bg-mid-gray/60";
    }
  };

  return (
    <button
      type="button"
      onClick={onClick}
      aria-expanded={isDropdownOpen}
      className={`flex min-h-[24px] min-w-[24px] items-center gap-2 hover:text-text/80 ${className}`}
      title={`Model status: ${displayText}`}
    >
      <div
        className={`w-2 h-2 shrink-0 rounded-full ${getStatusColor(status)}`}
      />
      <span
        className={
          isDropdownOpen === undefined ? "text-start" : "max-w-[45vw] truncate"
        }
      >
        {displayText}
      </span>
      {isDropdownOpen !== undefined && (
        <svg
          className={`w-3 h-3 transition-transform ${isDropdownOpen ? "rotate-180" : ""}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M19 9l-7 7-7-7"
          />
        </svg>
      )}
    </button>
  );
};

export default ModelStatusButton;
