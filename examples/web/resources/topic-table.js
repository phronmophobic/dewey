
document.addEventListener('DOMContentLoaded', function () {
  let table = new DataTable('#example', {
    // ajax: function (d, cb) {
    
    //       fetch('data/repos.json')
    //           .then(response => response.json())
    //     // .then( data => console.log(data));
    //           .then(data => cb(data));
    //   },
    order:[0, 'asc']
    // columns: [
    //   { data: 'topic', title: 'stars' },
    //   { data: 'name', title: 'name' },
    //   { data: 'description', title: 'description' },
    //   { data: 'owner', title: 'owner' },
    //   { data: 'url', title: 'url' ,
    //     render: function (data){
    //       var a = document.createElement('a');
    //       a.href = data;
    //       a.appendChild(document.createTextNode(data));
    //       var html = a.outerHTML;
    //       return html;
    //     }
    //   },
    //   { data: 'topics', title: 'topics',
    //     render: function (data){
    //       return data.join(', ');
    //     }
    //   }
    // ]
  } );
} );

