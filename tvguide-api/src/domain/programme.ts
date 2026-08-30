export interface NlzietProgrammeTarget {
  kind: 'replay';
  contentItemId: string;       // exact 22 base64url-tekens
  assetId: string;             // exact 32 hex-tekens
  channelId: string;           // NLZIET EPG-zender-ID, bijvoorbeeld "npo1" of "canvas"
  isReplayAllowed: boolean;
  isRestartAllowed: boolean;
}

export interface Programme {
  id: string;            // db_id; decimale string die in een signed 64-bit Kotlin Long past
  channelId: string;     // verwijst naar Channel.id (bv "npo1"), niet de sourceId
  title: string;
  start: string;         // RFC 3339 UTC-instant, bv. "2026-08-29T18:00:00.000Z"
  end: string;           // RFC 3339 UTC-instant
  description: string | null;
  imageUrl: string | null;
  genre: string | null;
  isLive: boolean;
  isRerun: boolean;
  isPremiere: boolean;
  ageRating: string | null; // genormaliseerd naar Kijkwijzer-code (bv. "12", "AL") of null
  nlziet?: NlzietProgrammeTarget | null; // Exact NLZIET EPG afspeeldoel
  /** @deprecated Niet gebruiken voor een klik op een gidsprogramma. */
  nlzietId?: string | null;
}
