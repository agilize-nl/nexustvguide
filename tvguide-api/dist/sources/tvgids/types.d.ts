export interface RawProgramme {
    s: string;
    e: string;
    db_id: string;
    title: string;
    descr?: string;
    inhoud?: string;
    algemene_inhoud?: string;
    img?: string;
    g_id?: string;
    subgenre?: string;
    tip?: string;
    rerun?: string;
    live?: string;
    is_premiere?: string;
    ei?: string;
    is_type?: string;
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
