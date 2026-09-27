import { createContext, useContext } from "react";
import type { SidebarSection } from "../Sidebar";

// lets a page send the user to the one place a setting lives instead of
// duplicating the control, e.g. Cleanup linking to its model in Models
export const SettingsNavigationContext = createContext<
  (section: SidebarSection) => void
>(() => {});

export const useSettingsNavigation = () =>
  useContext(SettingsNavigationContext);
