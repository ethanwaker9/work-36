import { BallotBuilder } from "./oylama-crypto.js";

let builder = null;

self.onmessage = (ev) => {
  const { pk, credential, choice } = ev.data;
  try {
    if (!builder || builder.pk.uid !== pk.uid) {
      self.postMessage({ type: "progress", step: "setup" });
      builder = new BallotBuilder(pk);
    }
    const out = builder.build(credential, choice, (msg) => self.postMessage({ type: "progress", ...msg }));
    self.postMessage({ type: "done", ...out });
  } catch (e) {
    self.postMessage({ type: "error", message: e && e.message ? e.message : String(e) });
  }
};
