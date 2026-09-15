const host = window.location.hostname;
window.splice_config = {
  auth: {
    algorithm: "rs-256",
    authority: "${SPLICE_APP_UI_AUTH_URL}",
    client_id: "${SPLICE_APP_UI_AUTH_CLIENT_ID}",
    token_audience: "${SPLICE_APP_UI_AUTH_AUDIENCE}",
  },
  services: {
    sv: {
      url: "https://" + window.location.host + "/api/sv",
    },
  },
  pollInterval: "${SPLICE_APP_UI_POLL_INTERVAL}",
  spliceInstanceNames: {
    networkName: "${SPLICE_APP_UI_NETWORK_NAME}",
    networkFaviconUrl: "${SPLICE_APP_UI_NETWORK_FAVICON_URL}",
    amuletName: "${SPLICE_APP_UI_AMULET_NAME}",
    amuletNameAcronym: "${SPLICE_APP_UI_AMULET_NAME_ACRONYM}",
    nameServiceName: "${SPLICE_APP_UI_NAME_SERVICE_NAME}",
    nameServiceNameAcronym: "${SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM}",
  },
  // dApp mode: run the governance UI backend-less. Login goes through a
  // CIP-103 RPC endpoint, reads come from Scan, and vote submissions are
  // exercised on a VoteDelegation contract through the dApp API.
  // SPLICE_APP_UI_DAPP_MODE_ENABLED defaults to false when unset.
  dappMode: {
    enabled: ${SPLICE_APP_UI_DAPP_MODE_ENABLED},
    scanUrl: "${SPLICE_APP_UI_DAPP_MODE_SCAN_URL}",
    cip103RpcUrl: "${SPLICE_APP_UI_DAPP_MODE_CIP103_RPC_URL}",
  },
};
