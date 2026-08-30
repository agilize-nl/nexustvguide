export interface RawProgramme {
  s: string;                 // unix-seconden als string (bv. "1787974500")
  e: string;                 // unix-seconden als string (bv. "1787975700")
  db_id: string;             // numerieke id als string (bv. "218748382")
  title: string;
  descr?: string;            // bevat HTML, bv. "<html><p>...</p></html>"
  inhoud?: string;           // platte tekst
  algemene_inhoud?: string;  // platte tekst
  img?: string;              // URL of leeg
  g_id?: string;             // genre-id numeriek
  subgenre?: string;         // leesbaar genre
  tip?: string;              // "true" / "false"
  rerun?: string;            // "true" / "false"
  live?: string;             // "true" / "false"
  is_premiere?: string;      // "true" / "false"
  ei?: string;               // rommelig veld: "", "6", "12", "H", "live", "tip"
  is_type?: string;          // "film", "serie", etc.
}

export interface RawChannelBucket {
  ch_id: string;
  prog: RawProgramme[];
}

export interface RawProgramsEnvelope {
  version: string;
  versionmessage?: Record<string, unknown>;
  data: Record<string, RawChannelBucket> | RawChannelBucket[];
}

export interface RawChannelItem {
  ch_id: string;
  ch_name: string;
  ch_short: string;
  slug: string;
  vidurli?: string;
  vidurla?: string;
  default: boolean;
}

export interface RawChannelsEnvelope {
  version: string;
  versionmessage?: Record<string, unknown>;
  data: RawChannelItem[];
}
