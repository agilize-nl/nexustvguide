export interface Channel {
  id: string;            // stabiele eigen id, bv "npo1"
  sourceId: string;      // tvgids ch_id, bv "1"
  name: string;
  logoUrl: string | null;
  inNlziet: boolean;     // stuurt de zenderselectie
  nlzietSlug: string | null;
  sortOrder: number;
}
