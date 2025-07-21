#include<bits/stdc++.h>
using namespace std;
typedef  long long ll;
typedef vector<ll> vec;
typedef map<string,int> mp;
#define cy cout<<"YES"<<endl;
#define cn cout<<"NO"<<endl;
#define all(x) (x).begin(), (x).end()
#define fast ios::sync_with_stdio(false);cin.tie(NULL);cout.tie(NULL);
#define pb push_back
#define pf push_front
#define pob pop_back
#define pof pop_front
#define mfor(i,n) for(int i=0;i<(n);i++)
#define rfor(i,n) for(int i=(n-1);i>0;i--)
//string s="abcdefghijklmnopqrstuvwxyz";
int ar[1001];
const ll mx= 1000;
void solve(){
    ll n,r,x=2023,c=1;
    cin>>n>>r;
    vec v(n);
    for(int i=0;i<n;i++){
        cin>>v[i];
        c*=v[i];
    }
    if(c==x){
        cy;
        for(int i=0;i<r;i++){
            cout<<1<<" ";
        }
        cout<<endl;
    }
    else{
        x=x/c;
        if(x*c!=2023){
            cn;
        }
        else{
            cy;
            cout<<x<<" ";
            for(int i=0;i<r-1;i++){
                cout<<1<<" ";
            }
            cout<<endl;
        }
    }
}
int main(){
    fast;
    int t;
    cin>>t;
    while (t--){
        solve();
    }   
}