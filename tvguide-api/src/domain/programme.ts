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
  nlzietId?: string | null; // Optionele NLZIET content/programma-ID voor directe doorschakeling
}
