export interface Channel {
    id: string;
    sourceId: string;
    name: string;
    logoUrl: string | null;
    inNlziet: boolean;
    nlzietSlug: string | null;
    nlzietChannelId: string | null;
    sortOrder: number;
}
