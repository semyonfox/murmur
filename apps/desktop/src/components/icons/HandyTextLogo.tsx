import React from "react";

const HandyTextLogo = ({
  width,
  height,
  className,
}: {
  width?: number;
  height?: number;
  className?: string;
}) => (
  <svg
    width={width}
    height={height}
    className={className}
    viewBox="0 0 258 64"
    fill="none"
    role="img"
    aria-label="Murmur"
    xmlns="http://www.w3.org/2000/svg"
  >
    <rect x="3" y="3" width="58" height="58" rx="18" fill="#183631" />
    <path
      d="M16 27v10m8-17v24m8-31v38m8-25v12m8-9v6"
      stroke="#8CE8C5"
      strokeWidth="5.5"
      strokeLinecap="round"
    />
    <path
      d="M76 45V27c0-4 2.8-7 6.7-7s6.7 3 6.7 7v18m0-18c0-4 2.8-7 6.7-7s6.7 3 6.7 7v18M113 22v14c0 6 3.2 9.5 8.5 9.5S130 42 130 36V22m0 14v9m13 0V22m0 12c0-8 4.2-13 12.5-13M168 45V27c0-4 2.8-7 6.7-7s6.7 3 6.7 7v18m0-18c0-4 2.8-7 6.7-7s6.7 3 6.7 7v18M205 22v14c0 6 3.2 9.5 8.5 9.5S222 42 222 36V22m0 14v9m13 0V22m0 12c0-8 4.2-13 12.5-13"
      stroke="currentColor"
      strokeWidth="5.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

export default HandyTextLogo;
