package dev.aura.player;

import android.net.Uri;import android.provider.DocumentsContract;import java.util.*;

/** An indexed folder hierarchy: direct songs belong to their folder, never to every ancestor. */
final class FolderTree {
    record Part(String id,String name) {}
    static final class Node {
        Track first;private int trackCount;final String id,name;final Node parent;final Map<String,Node> children=new LinkedHashMap<>();final List<Track> songs=new ArrayList<>();
        Node(String id,String name,Node parent){this.id=id;this.name=name;this.parent=parent;}
        Node child(String id,String name){return children.computeIfAbsent(id,k->new Node(id,name,this));}
        int count(){return trackCount;}
    }
    final Node root=new Node("","음악 폴더",null);final Map<String,Node> nodes=new HashMap<>();boolean legacy;
    FolderTree(List<Track> tracks){nodes.put("",root);for(Track track:tracks){if(root.first==null)root.first=track;root.trackCount++;String storage=track.drive()?"drive":"local";Node node=root.child(storage,track.sourceLabel());node.trackCount++;if(node.first==null)node.first=track;nodes.put(node.id,node);List<Part> path=track.folderPath;if(path.isEmpty()){legacy=true;path=fallback(track);}for(Part part:path){String id=node.id+"/"+Uri.encode(part.id());node=node.child(id,part.name());node.trackCount++;if(node.first==null)node.first=track;nodes.put(node.id,node);}node.songs.add(track);}}
    Node closest(String id){String cursor=id;while(!nodes.containsKey(cursor)&&!cursor.isEmpty()){int slash=cursor.lastIndexOf('/');cursor=slash<0?"":cursor.substring(0,slash);}return node(cursor);}
    Node node(String id){return nodes.getOrDefault(id,root);}
    static List<Part> fallback(Track track){List<Part> out=new ArrayList<>();String[] parts=track.folder.split("/");if(track.drive()){String account=Objects.toString(Uri.parse(track.uri).getAuthority(),"");for(int i=0;i<parts.length;i++)if(!parts[i].isEmpty())out.add(new Part(i==parts.length-1?account+":"+track.folderId:account+":legacy:"+String.join("/",Arrays.copyOf(parts,i+1)),parts[i]));}else{String root="legacy-local",name="기기 음악";try{Uri uri=Uri.parse(track.uri);if(DocumentsContract.isTreeUri(uri)){String tree=DocumentsContract.getTreeDocumentId(uri);root=uri.getAuthority()+":"+tree;name=tree.substring(Math.max(tree.lastIndexOf('/'),tree.lastIndexOf(':'))+1);if(name.isEmpty())name="기기 저장소";}}catch(Exception ignored){}out.add(new Part(root,name));for(String part:parts)if(!part.isEmpty()&&!part.equals("선택한 폴더"))out.add(new Part(part,part));}if(out.isEmpty())out.add(new Part(track.folderId.isEmpty()?"root":track.folderId,"음악"));return out;}
}
