import type { SVGProps } from 'react';

export function VolcanoIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    >
      {/* Mountain body — right rim slightly lower for natural look */}
      <path d="M2 22 L9 12 L12 15 L15 13 L22 22 Z" />
      {/* Eruption plume stems */}
      <path d="M12 12 L12 8" />
      <path d="M10 10 L9 7" />
      <path d="M14 10.5 L15 7" />
      {/* Cloud puffs at plume tips */}
      <path d="M10 8 A2 2 0 1 1 14 8" />
      <path d="M7.5 7 A1.5 1.5 0 1 1 10.5 7" />
      <path d="M13.5 7 A1.5 1.5 0 1 1 16.5 7" />
    </svg>
  );
}
