import React from "react";
import { useLocation, useParams } from "react-router-dom";
import PreCheckPanel from "../components/PreCheckPanel";

export default function CombinationPreCheckPage() {
  const { combinationId } = useParams();
  const location = useLocation();
  const fromSignoff = location.state?.from === "signoff";
  return <PreCheckPanel combinationId={combinationId} fromSignoff={fromSignoff} />;
}
