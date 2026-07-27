import React from "react";
import { useLocation, useParams } from "react-router-dom";
import PreCheckPanel from "../components/PreCheckPanel";

export default function ServerPreCheckPage() {
  const { serverId } = useParams();
  const location = useLocation();
  const fromSignoff = location.state?.from === "signoff";
  return <PreCheckPanel serverId={serverId} fromSignoff={fromSignoff} />;
}
