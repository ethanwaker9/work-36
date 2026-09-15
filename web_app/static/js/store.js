function read(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}

function write(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch {}
}

export const vault = {
  credential(email, eid) { return read(`oylama.cred:${email}:${eid}`, null); },
  saveCredential(email, eid, text) { write(`oylama.cred:${email}:${eid}`, text); },
  forgetCredential(email, eid) { try { localStorage.removeItem(`oylama.cred:${email}:${eid}`); } catch {} },
  receipts(email) { return read(`oylama.receipts:${email}`, []); },
  addReceipt(email, receipt) {
    const list = read(`oylama.receipts:${email}`, []);
    list.unshift(receipt);
    write(`oylama.receipts:${email}`, list.slice(0, 100));
  },
  clearReceipts(email) { write(`oylama.receipts:${email}`, []); }
};
