import { useEffect, useState } from "react";
import { jsonRequest } from "./api";
import { Console } from "./Console";
import { Login } from "./Login";
import { SignalIcon } from "./SignalIcon";

export default function App() {
  const [session, setSession] = useState<"checking" | "anonymous" | "authenticated">("checking");

  useEffect(() => {
    jsonRequest("/api/admin/session")
      .then(() => setSession("authenticated"))
      .catch(() => setSession("anonymous"));
  }, []);

  if (session === "checking") {
    return <div className="boot-screen"><SignalIcon /><span>Opening secure console…</span></div>;
  }
  if (session === "anonymous") {
    return <Login onAuthenticated={() => setSession("authenticated")} />;
  }
  return <Console onSignedOut={() => setSession("anonymous")} />;
}
