import React, {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { useTranslation } from "react-i18next";

export interface DropdownOption {
  value: string;
  label: string;
  description?: string;
  disabled?: boolean;
}

interface DropdownProps {
  options: DropdownOption[];
  className?: string;
  menuClassName?: string;
  selectedValue: string | null;
  onSelect: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  unavailableReasonId?: string;
  onRefresh?: () => void;
  label?: string;
}

export const Dropdown: React.FC<DropdownProps> = ({
  options,
  selectedValue,
  onSelect,
  className = "",
  menuClassName,
  placeholder = "Select an option...",
  disabled = false,
  unavailableReasonId,
  onRefresh,
  label,
}) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [placement, setPlacement] = useState<{
    above: boolean;
    maxHeight: number;
  }>();
  const menuId = useId();
  const unavailable = disabled || !!unavailableReasonId;
  const menuOpen = isOpen && !unavailable;

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(event.target as Node)
      ) {
        setIsOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  useLayoutEffect(() => {
    if (!menuOpen) return;
    const trigger = triggerRef.current;
    const menu = menuRef.current;
    if (!trigger || !menu) return;

    const updatePlacement = () => {
      const viewport = trigger
        .closest("main, [role='dialog']")
        ?.getBoundingClientRect();
      const anchor = trigger.getBoundingClientRect();
      const gap = 8;
      const above = anchor.top - Math.max(0, viewport?.top ?? 0) - gap;
      const below =
        Math.min(window.innerHeight, viewport?.bottom ?? window.innerHeight) -
        anchor.bottom -
        gap;
      const rootSize = Number.parseFloat(
        getComputedStyle(document.documentElement).fontSize,
      );
      const preferred = Math.min(menu.scrollHeight + 2, 15 * rootSize);
      const opensAbove = below < preferred && above > below;
      const maxHeight = Math.max(
        0,
        Math.min(preferred, Math.floor(opensAbove ? above : below)),
      );
      setPlacement((previous) =>
        previous?.above === opensAbove && previous.maxHeight === maxHeight
          ? previous
          : { above: opensAbove, maxHeight },
      );
    };

    updatePlacement();
    window.addEventListener("resize", updatePlacement);
    window.addEventListener("scroll", updatePlacement, true);
    return () => {
      window.removeEventListener("resize", updatePlacement);
      window.removeEventListener("scroll", updatePlacement, true);
    };
  }, [menuOpen, options.length]);

  const selectedOption = options.find(
    (option) => option.value === selectedValue,
  );

  const handleSelect = (value: string) => {
    onSelect(value);
    setIsOpen(false);
    triggerRef.current?.focus();
  };

  const handleToggle = () => {
    if (unavailable) return;
    if (!isOpen && onRefresh) onRefresh();
    setIsOpen(!isOpen);
  };

  return (
    <div
      className={`relative ${className}`}
      ref={dropdownRef}
      onKeyDown={(event) => {
        if (event.key === "Escape" && menuOpen) {
          event.preventDefault();
          event.stopPropagation();
          setIsOpen(false);
          triggerRef.current?.focus();
        }
      }}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget))
          setIsOpen(false);
      }}
    >
      <button
        type="button"
        ref={triggerRef}
        aria-label={
          label
            ? `${label}: ${selectedOption?.label || placeholder}`
            : undefined
        }
        aria-expanded={menuOpen}
        aria-controls={menuOpen ? menuId : undefined}
        aria-disabled={unavailableReasonId ? true : undefined}
        aria-describedby={unavailableReasonId}
        className={`px-2.5 py-[5px] text-sm bg-background border border-line/18 rounded-lg min-w-[200px] max-sm:min-w-0 w-full text-start grid grid-cols-[1fr_auto] gap-2 items-center ${
          unavailable
            ? "opacity-50 cursor-not-allowed"
            : "cursor-pointer hover:border-line/30"
        }`}
        onClick={handleToggle}
        disabled={disabled}
      >
        <span className="truncate">{selectedOption?.label || placeholder}</span>
        <svg
          className={`w-4 h-4 transition-transform duration-200 ${menuOpen ? "transform rotate-180" : ""}`}
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
      </button>
      <div
        id={menuId}
        ref={menuRef}
        data-open={menuOpen}
        style={{ maxHeight: placement?.maxHeight }}
        inert={!menuOpen}
        className={`dropdown-panel absolute bg-raised border border-line/15 rounded-lg shadow-raised z-50 p-1 max-h-60 overflow-y-auto overscroll-contain ${
          placement?.above
            ? "bottom-full mb-1 [--dropdown-origin:bottom]"
            : "top-full mt-1"
        } ${menuClassName ?? "left-0 right-0"}`}
      >
        {options.length === 0 ? (
          <div className="px-2 py-1 text-sm text-mid-gray">
            {t("common.noOptionsFound")}
          </div>
        ) : (
          options.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`w-full rounded-[calc(var(--radius-lg)-5px)] text-sm text-start hover:bg-mid-gray/15 ${
                option.description ? "px-3 py-2" : "px-2 py-1"
              } ${
                selectedValue === option.value
                  ? "bg-mid-gray/10 text-logo-primary"
                  : ""
              } ${option.disabled ? "opacity-50 cursor-not-allowed" : ""}`}
              onClick={() => handleSelect(option.value)}
              disabled={option.disabled}
              aria-pressed={selectedValue === option.value}
            >
              <span
                className={`block whitespace-normal break-words ${
                  option.description || selectedValue === option.value
                    ? "font-medium"
                    : ""
                }`}
              >
                {option.label}
              </span>
              {option.description && (
                <span className="mt-0.5 block whitespace-normal text-xs font-normal leading-snug text-mid-gray">
                  {option.description}
                </span>
              )}
            </button>
          ))
        )}
      </div>
    </div>
  );
};
