import React from "react";

const PAIR_STATUS_STYLE = {
  PENDING: { color: "red", label: "Pending" },
  IN_PROGRESS: { color: "yellow", label: "In Progress" },
  DELTA_READY: { color: "green", label: "Delta Ready" },
};

export function PairStatusBadge({ status }) {
  const style = PAIR_STATUS_STYLE[status] || { color: "gray", label: status };
  return <span className={`badge ${style.color}`}>{style.label}</span>;
}

export function ReadinessDot({ status }) {
  const color = status === "GREEN" ? "green" : status === "YELLOW" ? "yellow" : "red";
  const label = status === "GREEN" ? "Pairs Synced" : status === "YELLOW" ? "Pairs Syncing" : "Pairs Not Synced";
  return (
    <span>
      <span className={`dot ${color}`} />
      {label}
    </span>
  );
}

export function EscalationStatusBadge({ status }) {
  const color = status === "OPEN" ? "red" : "green";
  return <span className={`badge ${color}`}>{status}</span>;
}

const PRIORITY_STYLE = {
  LOW: { color: "gray", label: "Low" },
  MEDIUM: { color: "yellow", label: "Medium" },
  HIGH: { color: "red", label: "High" },
};

export function PriorityBadge({ priority }) {
  const style = PRIORITY_STYLE[priority] || { color: "gray", label: priority };
  return <span className={`badge ${style.color}`}>{style.label}</span>;
}
