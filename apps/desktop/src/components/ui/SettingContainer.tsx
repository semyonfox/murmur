import React, { useEffect, useRef, useState } from "react";
import { Tooltip } from "./Tooltip";

interface SettingContainerProps {
  title: string;
  description: string;
  children: React.ReactNode;
  descriptionMode?: "inline" | "tooltip";
  grouped?: boolean;
  layout?: "horizontal" | "stacked";
  disabled?: boolean;
  tooltipPosition?: "top" | "bottom";
}

export const SettingContainer: React.FC<SettingContainerProps> = ({
  title,
  description,
  children,
  descriptionMode = "tooltip",
  grouped = false,
  layout = "horizontal",
  disabled = false,
  tooltipPosition = "top",
}) => {
  const containerClasses = grouped
    ? "px-4 p-2"
    : "px-4 p-2 rounded-lg border border-line/12";

  if (layout === "stacked") {
    if (descriptionMode === "tooltip") {
      return (
        <div
          role="group"
          aria-label={title}
          aria-description={description}
          className={containerClasses}
        >
          <div className="flex items-center gap-2 mb-2">
            <h3
              className={`text-sm font-medium ${disabled ? "opacity-50" : ""}`}
            >
              {title}
            </h3>
            <SettingHelp
              title={title}
              description={description}
              position="top"
            />
          </div>
          <div className="w-full">{children}</div>
        </div>
      );
    }

    return (
      <div
        role="group"
        aria-label={title}
        aria-description={description}
        className={containerClasses}
      >
        <div className="mb-2">
          <h3 className={`text-sm font-medium ${disabled ? "opacity-50" : ""}`}>
            {title}
          </h3>
          <p className={`text-sm ${disabled ? "opacity-50" : ""}`}>
            {description}
          </p>
        </div>
        <div className="w-full">{children}</div>
      </div>
    );
  }

  // Horizontal layout (default)
  const horizontalContainerClasses = grouped
    ? "flex items-center justify-between gap-2 min-h-12 px-4 p-2 max-sm:flex-col max-sm:items-stretch"
    : "flex items-center justify-between gap-2 min-h-12 px-4 p-2 rounded-lg border border-line/12 max-sm:flex-col max-sm:items-stretch";

  if (descriptionMode === "tooltip") {
    return (
      <div
        role="group"
        aria-label={title}
        aria-description={description}
        className={horizontalContainerClasses}
      >
        <div className="min-w-0 max-w-2/3 max-sm:max-w-full break-words">
          <div className="flex items-center gap-2">
            <h3
              className={`text-sm font-medium ${disabled ? "opacity-50" : ""}`}
            >
              {title}
            </h3>
            <SettingHelp
              title={title}
              description={description}
              position={tooltipPosition}
            />
          </div>
        </div>
        <div className="relative min-w-0">{children}</div>
      </div>
    );
  }

  return (
    <div
      role="group"
      aria-label={title}
      aria-description={description}
      className={horizontalContainerClasses}
    >
      <div className="min-w-0 max-w-2/3 max-sm:max-w-full break-words">
        <h3 className={`text-sm font-medium ${disabled ? "opacity-50" : ""}`}>
          {title}
        </h3>
        <p className={`text-sm ${disabled ? "opacity-50" : ""}`}>
          {description}
        </p>
      </div>
      <div className="relative min-w-0">{children}</div>
    </div>
  );
};

const TOOLTIP_DELAY = 350;
const TOOLTIP_GRACE = 300;
let tooltipWarmUntil = 0;
let visibleTooltips = 0;

function SettingHelp({
  title,
  description,
  position,
}: {
  title: string;
  description: string;
  position: "top" | "bottom";
}) {
  const [open, setOpen] = useState(false);
  const targetRef = useRef<HTMLDivElement>(null);
  const pending = useRef<number | undefined>(undefined);

  const cancelPending = () => window.clearTimeout(pending.current);

  const show = () => setOpen(true);

  const hide = () => {
    cancelPending();
    if (open) {
      setOpen(false);
    }
  };

  useEffect(() => {
    if (!open) return;
    visibleTooltips += 1;
    const dismissOutside = (event: MouseEvent) => {
      if (
        event.target instanceof Node &&
        !targetRef.current?.contains(event.target)
      )
        setOpen(false);
    };
    const dismissOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", dismissOutside);
    document.addEventListener("keydown", dismissOnEscape);
    return () => {
      visibleTooltips -= 1;
      tooltipWarmUntil = performance.now() + TOOLTIP_GRACE;
      document.removeEventListener("mousedown", dismissOutside);
      document.removeEventListener("keydown", dismissOnEscape);
    };
  }, [open]);

  useEffect(() => () => window.clearTimeout(pending.current), []);

  return (
    <div
      ref={targetRef}
      className="relative"
      onMouseEnter={() => {
        cancelPending();
        if (visibleTooltips || performance.now() < tooltipWarmUntil) show();
        else pending.current = window.setTimeout(show, TOOLTIP_DELAY);
      }}
      onMouseLeave={hide}
    >
      <button
        type="button"
        className="grid size-[24px] place-items-center text-mid-gray cursor-help hover:text-logo-primary"
        aria-label={`More information: ${title}`}
        aria-expanded={open}
        onClick={() => {
          cancelPending();
          if (open) hide();
          else show();
        }}
      >
        <svg
          className="size-6 select-none"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
      </button>
      {open && (
        <Tooltip targetRef={targetRef} position={position}>
          <p className="text-sm text-center leading-relaxed">{description}</p>
        </Tooltip>
      )}
    </div>
  );
}
